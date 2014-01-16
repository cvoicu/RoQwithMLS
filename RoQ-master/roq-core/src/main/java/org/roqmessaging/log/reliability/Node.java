package org.roqmessaging.log.reliability;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.Timer;

import org.apache.log4j.Logger;
import org.roqmessaging.log.LogConfigDAO;
import org.roqmessaging.log.storage.LogManagement;
import org.roqmessaging.log.storage.MessageHandler;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Context;
import org.zeromq.ZMQ.Socket;


public class Node implements Runnable {

	static Logger log = Logger.getLogger(Node.class.getName());
	private String name = null;
	private String nodeAddress;
	private int configPort = 0;
	private int dataPort = 0;
	private int replicationFactor = 0;
	private int counter = 0;
	private Context context = null;
	private Socket server = null;
	private Socket config = null;
	private Socket data = null;
	private Socket host = null;
	private ArrayList<Socket> subSubSocks = new ArrayList<Socket>();
	private Socket subPubSock = null;
	private PriorityList pl = null;
	private ArrayList<ConnectedNode> replicaList = new ArrayList<ConnectedNode>();
	private ArrayList<ConnectedNode> masters = new ArrayList<ConnectedNode>();
	private ArrayList<NodeInfo> crashedList = new ArrayList<NodeInfo>();
	private ZMQ.Poller items = null;
	private long hb = 0;
	private int debug = 1;
	private int debug3 = 1;
	private boolean debug2 = true;
	private Thread hbThread;
	private LogManagement lm = null;
	private MessageHandler mh = null;
	//private long seqNumber = 0;
	private int nbAcks = 0;
	private String topic = null;
	private HashMap<String, ArrayList<Msg>> packages = new HashMap<String, ArrayList<Msg>>();
	
	public Node(LogConfigDAO properties, Context ctx, String name, int replicationFactor, String nodeAddress, int configPort, int dataPort, String seeds, MessageHandler mh) {
		this.name = name;
		this.nodeAddress = nodeAddress;
		this.configPort = configPort;
		this.dataPort = dataPort;
		this.replicationFactor = replicationFactor;
		this.mh = mh;
		this.pl = new PriorityList(true);
		this.pl.setArrayList(decodeSeeds(seeds));
		System.out.println("number of unique seeds: "+pl.getArrayList().size());
		try {
			this.lm = new LogManagement(properties);
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		context = ZMQ.context(1);
		
		host = ctx.socket(ZMQ.SUB);
		host.connect("inproc://"+name+"start");
		host.subscribe("".getBytes());
		
		server = ctx.socket(ZMQ.PULL);
		server.connect("inproc://"+name);
		
		config = context.socket(ZMQ.DEALER);
		config.bind("tcp://"+nodeAddress+":"+configPort);
		
		data = context.socket(ZMQ.DEALER);
		data.bind("tcp://"+nodeAddress+":"+dataPort);
		
		subPubSock = context.socket(ZMQ.PUB);
		subPubSock.bind("tcp://"+nodeAddress+":"+12340);
		
		//subSubSock = context.socket(ZMQ.SUB);
		//subSubSock.connect("tcp://"+addresOfExchanges);
		
		items = new ZMQ.Poller(100);
		items.register(host);
		items.register(server);
		items.register(config);
		items.register(data);

		hb = System.currentTimeMillis();
		connectToReplicas();
		Heartbeat hbt = new Heartbeat(name, 2000, replicaList, masters);
		hbThread = new Thread(hbt);
		hbThread.start();
		Timer tim = new Timer();
		tim.schedule(new HandleAck(this, replicaList, packages), 1500, 1500);
	}

	public void run() {
		//ArrayList<NodeInfo> nodesList = askForListOfNodes();
		//pl.setArrayList(nodesList);
		int a = 0;
		while (a<pl.getArrayList().size()) {
			System.out.println(pl.getArrayList().get(a).getName() + "counter: "+pl.getArrayList().get(a).getCounter());
			a++;
		}
		
		while(true) {
			items.poll();
			if(items.pollin(0)) {
				//host
				System.out.println(name+ " "+host.recvStr());
			}
			if(items.pollin(1)) {
				//server inproc
				//System.out.println("coucou");
				topic = server.recvStr();
				//seqNumber = Long.valueOf(server.recvStr());
				long offset = Long.valueOf(server.recvStr());
				int msgSize = Integer.valueOf(server.recvStr());
				ByteBuffer message = ByteBuffer.allocateDirect(msgSize);
				server.recvByteBuffer(message, 0);
				Msg msg = new Msg(topic, message, offset, msgSize);
				int i = 0;
				ArrayList<Msg> listPkgs = packages.get(topic);
				if(listPkgs == null) {
					packages.put(topic, new ArrayList<Msg>());
				}
				listPkgs = packages.get(topic);
				listPkgs.add(msg);
				//TODO check the memory
				while(i < replicaList.size()) {
					replicaList.get(i).addMsg(topic, msg);
					Socket data = replicaList.get(i).getData();
					data.send(name, ZMQ.SNDMORE);
					data.send(topic, ZMQ.SNDMORE);
					data.send(""+offset, ZMQ.SNDMORE);
					data.send(""+msgSize, ZMQ.SNDMORE);
					data.sendByteBuffer(message, 0);
					i++;
				}
			}
			if(items.pollin(2)) {
				//config
				String message = config.recvStr();
				
				if(message.compareTo("ack") == 0) {
					String from = config.recvStr();
					String tpc = config.recvStr();
					long offset = Long.valueOf(config.recvStr());
					int i = 0;
					while(i<replicaList.size()) {
						if(from.compareTo(replicaList.get(i).getName()) == 0) {
							ArrayList<Msg> listPkgs = packages.get(topic);
							int j = 0;
							while(j < listPkgs.size())  {
								if(listPkgs.get(j).getOffset() == offset) {
									listPkgs.get(j).getReplicas().put(from, true);
									break;
								}
								j++;
							}
							break;
						}
						i++;
					}
					if(checkAcks(tpc, offset)) {
						//in this case we can remove the buffer from memory.
						ArrayList<Msg> pkgs = null;
						pkgs = packages.get(tpc);
						int j = 0;
						while(j >= pkgs.size()) {
							if(offset == pkgs.get(j).getOffset()) {
								pkgs.remove(j);
								break;
							}
							j++;
						}
					}	
				}
				
				if(message.compareTo("heartbeat") == 0) {
					String heartbeatName = config.recvStr();
					//ArrayList<NodeInfo> nodesList = askForListOfNodes();
					//pl.setArrayList(nodesList);
					//log.info(name + " received heartbeat from "+ heartbeatName);
					int i = 0;
					while(i < replicaList.size()) {
						if(heartbeatName.compareTo(replicaList.get(i).getName()) == 0) {
							replicaList.get(i).setHeartbeat(System.currentTimeMillis());
						}
						i++;
					}
				}
				if(message.compareTo("announce crash") == 0) {
					String crashedName = config.recvStr();
					log.info(name + " is informed that node crashed: "+crashedName);
					handleCrash(crashedName);
					subPubSock.sendMore("announceCrash");
					subPubSock.send(crashedName);
				}
				if(message.compareTo("announce new node") == 0) {
					String newNode = config.recvStr();
					//log.info(name +" received new node: "+newNode);
					handleNewNode(newNode);
				}
				if(message.compareTo("announce new master node") == 0) {
					String newMasterNode = config.recvStr();
					handleNewMasterNode(newMasterNode);
				}
				if(message.compareTo("ask for list of nodes") == 0) {
					
					String listOfNodes = encodeNodeInfoList(pl.getArrayList());
					config.send(listOfNodes);
				}
			}
			if(items.pollin(3)) {
				String masterName = data.recvStr();
				String topic = data.recvStr();
				//long sn = Long.valueOf(data.recvStr());
				long offset = Long.valueOf(data.recvStr());
				int msgSize = Integer.valueOf(data.recvStr());
				ByteBuffer buf = ByteBuffer.allocateDirect(msgSize);
				data.recvByteBuffer(buf, 0);
				try {
					lm.store(name, masterName, topic, buf, false, offset);
				} catch (IOException e) {
					e.printStackTrace();
				}
				int i = 0;
				while (i < masters.size()) {
					if(masterName.compareTo(masters.get(i).getName()) == 0) {
						Socket sock = masters.get(i).getConfig();
						sock.send("ack", ZMQ.SNDMORE);
						sock.send(name, ZMQ.SNDMORE);
						sock.send(topic, ZMQ.SNDMORE);
						sock.send(""+offset);
					}
					i++;
				}
			}
			
			//TODO in case an ack is not received? stragtegy: resend the message and wait again. 3 times? 
			//after this if not ack receivend then replica node is dead and should find another replica node.
			
			int i = 0;				
			/*while(i < masters.size()) {
				if(items.pollin(3+i)) {
					System.out.println("verify");
					Socket data = masters.get(i).getData();
					String masterName = data.recvStr();
					String topic = data.recvStr();
					int msgSize = Integer.valueOf(data.recvStr());
					ByteBuffer buf = ByteBuffer.allocateDirect(msgSize);
					data.recvByteBuffer(buf, 0);
					try {
						lm.store(name+"XX", masterName, topic, buf);
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
				i++;
			}
			*/
			if(replicaList.size() < replicationFactor) {
				// check for new replicas if replicaList < replicationFactor
				//if((System.currentTimeMillis())%100 == 0) {
				//System.out.println("test1");
					connectToReplicas();
				//}
			}
			/*
			int md = 500;
			if(debug3%md == 0) {
				System.out.println(name +"  !!!ask for list of nodes");
				ArrayList<NodeInfo> nodesList = askForListOfNodes();
				pl.setArrayList(nodesList);
				md = md * md;
			}
			debug3++;
			*/
/*
			if(debug%500000 == 0) {
				if(debug2) {
				int k = 0;
				//System.out.print(name+ " : [");
				//while(k < pl.getArrayList().size()) {
				//	System.out.print("   " +pl.getArrayList().get(k).getName());
				//	k++;
				//}
				if(name.compareTo("Node0x") == 0) {
				System.out.println(" ALERT!!!!!!!!!");
				hbThread.stop();
				debug2=false;
				break;
				}
				}
			}*/
			debug++;
			if(System.currentTimeMillis() - hb > 2000) {
				/*
				i = 0;
				while(i < replicaList.size()) {
					replicaList.get(i).getConfig().sendMore("heartbeat");
					replicaList.get(i).getConfig().send(name);
					i++;
				}*/
				hb = System.currentTimeMillis();
			}
			
			
			i = 0;
			while(i < replicaList.size()) {
				if(System.currentTimeMillis() - replicaList.get(i).getHeartbeat() > 6000) {
					System.out.println("6 seconds passed");
					handleCrash(replicaList.get(i).getName());
				}
				i++;
			}
			
		}
		//server.close();
		//context.term();
	}
	
	public String getNodeName() {
		return this.name;
	}
	
	public boolean checkAcks(String topic, long offset) {
		ArrayList<Msg> listPkgs = packages.get(topic);
		int i = 0;
		while(i < listPkgs.size()) {
			if(listPkgs.get(i).getOffset() == offset) {
				Msg msg = listPkgs.get(i);
				Collection<Boolean> col = msg.getReplicas().values();
				Iterator<Boolean> it = col.iterator();
				while(it.hasNext()) {
					if(!it.next()) {
						return false;
					}
				}
			}
			i++;
		}
		return true;
	}
	
	public void initAcks(String topic) {
		int i = 0;
		while(i < replicaList.size()) {
			replicaList.get(i).getMsgs().remove(topic);
			i++;
		}
	}
	
	public String encodeNodeInfoList(ArrayList<NodeInfo> list) {
		String result = "";
		int i = 0;
		while(i < list.size()) {
			if(i == 0) {
				result = encodeNodeInfo(list.get(i));
			}
			else {
				result = result + "#" + encodeNodeInfo(list.get(i));
			}
			i++;
		}		
		return result;
	}
	
	public ArrayList<NodeInfo> decodeNodeInfoList(String message) {
		ArrayList<NodeInfo> list = new ArrayList<NodeInfo>();
		String[] items = message.split("#");
		int i = 0;
		while(i < items.length) {
			NodeInfo ni = decodeNodeInfo(items[i]);
			list.add(ni);
			i++;
		}
		return list;
	}
	
	public String encodeNodeInfo(NodeInfo ni) {
		String message = null;
		message = ni.getName()+" "+ni.getAddress()+" "+ni.getConfigPort()+" "+ni.getDataPort()+" "+ni.getCounter();
		return message;
	}
	
	public NodeInfo decodeNodeInfo(String message) {
		NodeInfo ni = null;
		String[] items = message.split(" ");
		ni = new NodeInfo(items[0], items[1], Integer.valueOf(items[2]), Integer.valueOf(items[3]));
		ni.setCounter(Integer.valueOf(items[4]));
		return ni;
	}
	
	private ArrayList<NodeInfo> decodeSeeds(String seeds) {
		ArrayList<NodeInfo> seedsList = new ArrayList<NodeInfo>();
		String[] s = seeds.split("#");
		List<String> list = Arrays.asList(s);
		Set<String> set = new HashSet<String>(list);
		String[] uniqueSeeds = new String[set.size()];
		set.toArray(uniqueSeeds);
		int i = 0;
		while(i < uniqueSeeds.length) {
			String[] seed = uniqueSeeds[i].split(":");
			if(seed.length >= 2) {
				String address = seed[0];
				int cport = Integer.valueOf(seed[1]);
				int dport = cport+1;
				
				NodeInfo ni = new NodeInfo("Node"+uniqueSeeds[i]+"x", address, cport, dport);
				seedsList.add(ni);
			}
			i++;
		}
		return seedsList;
	}
	
	public ArrayList<NodeInfo> askForListOfNodes() {
		ArrayList<NodeInfo> newList = new ArrayList<NodeInfo>();
		ArrayList<NodeInfo> mergedList = new ArrayList<NodeInfo>();
		String message = null;
		Socket socket = context.socket(ZMQ.DEALER);
		int j = 0;
		boolean connected = false;
		while(j < pl.getArrayList().size()) {
			int i = new Random().nextInt(pl.getArrayList().size());
			try{
				if(pl.getArrayList().get(i).getName().compareTo(name) != 0) {
					socket.connect("tcp://"+pl.getArrayList().get(i).getAddress()+":"+pl.getArrayList().get(i).getConfigPort());
					connected = true;
					break;
				}
			} catch(Exception e) {
				System.out.println("Cannot connect to ..."); //+pl.getArrayList().get(i).getAddress()+":"+pl.getArrayList().get(i).getConfigPort());
			}
			j++;
		}
		if (connected) {
			socket.send("ask for list of nodes");
			//System.out.println("test");
			message = socket.recvStr();
		}
		else {
			System.out.println(name +" The connection is not working");
		}
		if(message != null) {
			newList = decodeNodeInfoList(message);
			
			System.out.println(name+ "size of mergedlist "+ mergedList.size());
		}
		//log.info(name + " received the list of nodes: "+ message);
		mergedList = mergeListsOfNodes(pl.getArrayList(), newList);
		return mergedList;
	}
	
	private ArrayList<NodeInfo> mergeListsOfNodes(ArrayList<NodeInfo> localList, ArrayList<NodeInfo> receivedList) {
		ArrayList<NodeInfo> mergedList = new ArrayList<NodeInfo> ();
		
		if(localList.size() == 0) {
			return receivedList;
		}
		if(receivedList.size() == 0) {
			return localList;
		}
		
		int i = 0;
		while(i < localList.size()) {
			int j = 0;
			while(j < receivedList.size()) {
				if(localList.get(i).getName().compareTo(receivedList.get(j).getName()) == 0) {
					if(receivedList.get(j).getCounter() >= localList.get(i).getCounter()) {
						mergedList.add(receivedList.get(j));
					}
					else {
						mergedList.add(localList.get(i));
					}
					break;
				}
				j++;
			}
			if(j==receivedList.size()) {
				mergedList.add(localList.get(i));
			}
			i++;
		}
		i=0;
		while(i<receivedList.size()) {
			int k = 0;
			while(k<mergedList.size()){
				if(receivedList.get(i).getName().compareTo(mergedList.get(k).getName()) == 0) {
					break;
				}
				k++;
			}
			if(k < mergedList.size()) {
				mergedList.add(receivedList.get(i));
			}
			i++;
		}
		
		return mergedList;
	}

	public void connectToReplicas() {
		
		ArrayList<NodeInfo> blackList = new ArrayList<NodeInfo>();
		NodeInfo thisNode = new NodeInfo(name, nodeAddress, configPort, dataPort);
		thisNode.setCounter(masters.size());
		int j=0;
		while(j<replicaList.size()) {
			NodeInfo ni = new NodeInfo(replicaList.get(j).getName(), "", 0, 0);
			blackList.add(ni);
			j++;
		}
		blackList.add(thisNode);
		
		//ArrayList<NodeInfo> nodesList = askForListOfNodes();
		//pl.setArrayList(nodesList);
		
		int i = replicaList.size();
		while(i < replicationFactor) {
			NodeInfo ni = pl.pick(blackList);
			if(ni != null) {
				Socket config = context.socket(ZMQ.DEALER);
				config.connect("tcp://"+ni.getAddress()+":"+ni.getConfigPort());
				
				Socket data = context.socket(ZMQ.DEALER);
				data.connect("tcp://"+ni.getAddress()+":"+ni.getDataPort());
				
				ConnectedNode replica = new ConnectedNode(ni.getName(), config, data);
				replicaList.add(replica);
				blackList.add(ni);
				//config.send("increase conter");
				config.sendMore("announce new node");
				config.send(encodeNodeInfo(thisNode));
				config.sendMore("announce new master node");
				config.send(encodeNodeInfo(thisNode));
				log.info(name + " connected to replica "+ ni.getName());
			}
			i++;
		}
	}
	
	public void annouceCrash(String crashedNode) {
		int i = 0;
		while(i < replicaList.size()) {
			replicaList.get(i).getConfig().sendMore("announce crash");
			replicaList.get(i).getConfig().send(crashedNode);
			i++;
		}
	}
	
	public void handleNewNode(String newNode) {
		NodeInfo ni = decodeNodeInfo(newNode);
		int i = 0;
		while(i < pl.getArrayList().size()) {
			if(ni.getName().compareTo(pl.getArrayList().get(i).getName()) == 0) {
				//pl.getArrayList().get(i).setCounter(ni.getCounter());
				break;
			}
			i++;
		}
		if (i == pl.getArrayList().size()) {
		i=0;
		pl.add(ni);
		//newNodeList.add(ni);
		while(i < replicaList.size()) {
			replicaList.get(i).getConfig().sendMore("announce new node");
			replicaList.get(i).getConfig().send(newNode);
			i++;
		}
		}
	}
	
	public void handleNewMasterNode(String newNode) {
		NodeInfo ni = decodeNodeInfo(newNode);
		Socket config = context.socket(ZMQ.DEALER);
		config.connect("tcp://"+ni.getAddress()+":"+ni.getConfigPort());
		
		Socket data = context.socket(ZMQ.DEALER);
		data.connect("tcp://"+ni.getAddress()+":"+ni.getDataPort());
		
		items.register(data);
		
		ConnectedNode cn = new ConnectedNode(ni.getName(), config, data);
		masters.add(cn);
		log.info(name + " new master connected : "+ni.getName() );
	}
	
	public void handleCrash(String crashedName) {
		NodeInfo crashedNode = null;
		int i = 0; 
		while(i < pl.getArrayList().size()) {
			if(crashedName.compareTo(pl.getArrayList().get(i).getName()) == 0) {
				crashedNode = pl.getArrayList().get(i);
			}
			i++;
		}
		if(crashedNode == null) {
			crashedNode = new NodeInfo(crashedName, "", 0, 0);
		}
		
		
		
		//take an action in case the crashed node is a raplica for this node.
		i = 0;
		while(i < replicaList.size()) {
			if(crashedName.compareTo(replicaList.get(i).getName()) == 0) {
				replicaList.remove(i);
				//TODO take more actions! for example find another replica
			}
			i++;
		}
		
		//take an action in case the crashed node is a master.
		i = 0;
		while(i < masters.size()) {
			if(crashedName.compareTo(masters.get(i).getName()) == 0) {
				masters.remove(i);
				//TODO take more actions, for example decide who will be the active node.
			}
			i++;
		}
		
		//announce replicas node that a node has crashed if the node isn't in the crashed list.
		i = 0;
		while(i < crashedList.size()) {
			if(crashedName.compareTo(crashedList.get(i).getName()) == 0) {
				break;
			}
			i++;
		}
		if(i==crashedList.size()) {
			i = 0;
			while(i < replicaList.size()) {
				replicaList.get(i).getConfig().sendMore("announce crash");
				replicaList.get(i).getConfig().send(crashedName);
				i++;
			}
			i = 0;
			while(i < masters.size()) {
				masters.get(i).getConfig().sendMore("announce crash");
				masters.get(i).getConfig().send(crashedName);
				i++;
			}
			crashedList.add(crashedNode);
		}				
	}
	
}
