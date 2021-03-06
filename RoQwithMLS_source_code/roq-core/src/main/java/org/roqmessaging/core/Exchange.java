/**
 * Copyright 2012 EURANOVA
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * @author Nam-Luc Tran, Sabri Skhiri
 */

package org.roqmessaging.core;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Timer;

import org.apache.log4j.Logger;
import org.roqmessaging.core.data.StatDataState;
import org.roqmessaging.core.interfaces.IStoppable;
import org.roqmessaging.core.timer.ExchangeStatTimer;
import org.roqmessaging.core.timer.Heartbeat;
import org.roqmessaging.core.utils.RoQUtils;
import org.roqmessaging.log.MessagePersistence;
import org.roqmessaging.log.storage.TopicBuffer;
import org.roqmessaging.state.ProducerState;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Socket;


/**
 * Class Exchange
 * <p> Description: The main component of the logical queue. All messages must 
 * go through this element.
 * 
 * @author Nam-Luc Tran, Sabri Skhiri, Quentin Dugauthier
 */
public class Exchange implements Runnable, IStoppable {
	
	private Logger logger = Logger.getLogger(Exchange.class);

	private HashMap<String, ProducerState> knownProd;
	private ZMQ.Context context;
	private ZMQ.Socket frontendSub;
	private ZMQ.Socket backendPub;
	private ZMQ.Socket monitorPub;
	private ZMQ.Socket pubInfoRep;
	private String s_frontend;
	private String s_backend;
	private String s_monitor;
	private StatDataState statistic=null;
	public int frontEnd, backEnd;
	//the heart beat and the stat
	private Timer timer = null;
	private volatile boolean active=false;
	private String ID = null;
	
	//Shutdown thread
	private ShutDownMonitor shutDownMonitor = null;

	//Timeout value of the front sub poller
	private long timeout=80;
	//cvoicu:
	private ZMQ.Socket stop;
	
	//cvoicu:
	boolean useLog = false;
	MessagePersistence ml = null;

	/**
	 * Notice that we start a shutdown request socket on frontEnd port +1
	 * @param frontend the front port
	 * @param backend the back port
	 * @param monitorHost the address of the monitor to bind  tcp:// monitor:monitorPort;
	 * @param statHost tcp://monitor:statport
	 */
	public Exchange(int frontend, int backend, String monitorHost, String statHost, String logAddress, String seeds, String propertyFile) {
		knownProd = new HashMap<String, ProducerState>();
		this.statistic = new StatDataState();
		this.statistic.setProcessed(0);
		this.statistic.setThroughput(0);
		this.statistic.setStatHost(statHost);
		this.statistic.setMax_bw( 5000); // bandwidth limit, in bytes/minute, per producer
		this.s_frontend = "tcp://*:" + frontend;
		this.s_backend = "tcp://*:" + backend;
		this.s_monitor = monitorHost;

		this.context = ZMQ.context(1);
		this.frontendSub = context.socket(ZMQ.SUB);
		this.backendPub = context.socket(ZMQ.PUB);
		//cvoicu:
		this.stop = context.socket(ZMQ.SUB);
		stop.connect("tcp://172.31.44.172:10110");
		stop.subscribe("stop".getBytes());
		
		this.ID = "XChange "+System.currentTimeMillis();
		
		// Caution, the following method as well as setSwap must be invoked before binding
		// Use these to (double) check if the settings were correctly set  
		// logger.info(this.backend.getHWM());
		// logger.info(this.backend.getSwap());
		setSocketOptions(this.backendPub);
		setSocketOptions(this.frontendSub);
	    
		this.frontendSub.bind(s_frontend);
		this.frontendSub.subscribe("".getBytes());

		this.backendPub.bind(s_backend);
		this.monitorPub = context.socket(ZMQ.PUB);
		
		//The channel on which the publisher will notifies their deconnection
		this.pubInfoRep =  context.socket(ZMQ.REP);
		this.pubInfoRep.bind("tcp://*:" +(backend+2));
		
		this.monitorPub.connect(s_monitor);
		this.frontEnd=frontend;
		this.backEnd= backend;
		this.active = true;
		
		if(logger.isInfoEnabled()){
			logger.info("BackendSub: SndHWM="+this.backendPub.getSndHWM()+" RcvHWM="+this.backendPub.getRcvHWM());
	        logger.info("FrontendSub: SndHWM="+this.frontendSub.getSndHWM()+" RcvHWM="+this.frontendSub.getRcvHWM());
		}
			
		//cvoicu:		
		//ml = new MessagePersistence(logAddress, seeds, propertyFile);
		//this.useLog = ml.getUseLog();
		
		//initiatlisation of the shutdown thread
		this.shutDownMonitor = new ShutDownMonitor(backend+1, this);
		new Thread(shutDownMonitor).start();
		logger.debug("Started shutdown monitor on "+ (backend+1));
	}

	private void setSocketOptions(Socket sock) {
		sock.setSndHWM(100000);  
		sock.setRcvHWM(100000);
		
	}

	/**
	 * Log the size of the message in the producer state.
	 * @param msgsize the size of the message sent
	 * @param prodID sent by this producer
	 */
	private void logPayload(long msgsize, String prodID) {
		statistic.setThroughput(statistic.getThroughput()+ msgsize);
		ProducerState state = knownProd.get(prodID);
		if(state!=null){
			state.addBytesSent(msgsize);
		}else{
			state = new ProducerState(prodID);
			state.addBytesSent(msgsize);
			knownProd.put(prodID,state );
			logger.debug("A new challenger has come ("+prodID+") on "+ID+", they are now :" + knownProd.size());
		}
	}

	/**
	 * @return the most important producer from the list based on the 
	 * number of byte sent.
	 */
	public  String getMostProducer() {
		if (!knownProd.isEmpty()) {
			long max = 0;
			String ID = "";
			for (ProducerState state_i : knownProd.values()) {
				if(state_i.getBytesSent()>max){
					max = state_i.getBytesSent();
					ID = state_i.getID();
				}
			}
			String IDNBR = (ID.compareTo("")==0) ? "cvoicu: no producer": Long.toString(knownProd.get(ID).getBytesSent());
			return ID + "," + IDNBR;
		}
		return "x,x";
	}

	public void run() {
		logger.info("Exchange Started");
		timer = new Timer();
		Heartbeat heartBeatTimer = new Heartbeat(this.s_monitor, this.frontEnd, this.backEnd );
		timer.schedule(heartBeatTimer, 5, 2000);
		ExchangeStatTimer exchStatTimer = new ExchangeStatTimer(this, this.statistic);
		//This is important that the exchange stat timer is triggered every second, since it computes throughput in byte/min.
		timer.schedule(exchStatTimer, 100, 60000);
		int part;
		String prodID= null;
		//Adding the poller
		ZMQ.Poller poller = new ZMQ.Poller(2);
		poller.register(this.frontendSub);
		poller.register(this.pubInfoRep);
		poller.register(this.stop);
		
		//cvoicu:
		String topic = null;
		logger.info("cvoicu: start MLS");
		
		while (this.active) {
			byte[] message;
			part = 0;
			//Set the poll time out, it returns either when someting arrive or when it time out
			poller.poll(this.timeout);
			if (poller.pollin(0)) {
				do {
					/*
					 *  ** Message multi part construction ** 1: routing key 2:
					 * producer ID 3: payload
					 */

					message = frontendSub.recv(0);
					part++;
					if (part == 1) {
						topic = new String(message);
					}
					if (part == 2) {
						prodID=bytesToStringUTFCustom(message);
					}
					if (part == 3) {
						logPayload(message.length, prodID);
						//if(useLog) {
						//	ml.handle(topic, prodID, message);
						//}
					}
					backendPub.send(message, frontendSub.hasReceiveMore() ? ZMQ.SNDMORE : 0);
				} while (this.frontendSub.hasReceiveMore() && this.active);
				this.statistic.processed++;
			}
			else if(poller.pollin(1)){
				//A publisher sends a deconnexion event
				byte[] info = pubInfoRep.recv(0);
				String mInfo = new String(info);
				String[] arrayInfo = mInfo.split(","); //CODE, ID
				if(knownProd.remove(arrayInfo[1])!=null){
					logger.info("Successfully removed publisher "+arrayInfo[1] +" remains "+ knownProd.size() + " publishers.");
					this.pubInfoRep.send(Integer.toString(RoQConstant.OK).getBytes(), 0);
				}else{
					logger.warn("The publisher "+ arrayInfo[1]+"  is not known");
					this.pubInfoRep.send(Integer.toString(RoQConstant.FAIL).getBytes(), 0);
				}
			}
			else if(poller.pollin(2)) {
				String exchAddress = RoQUtils.getInstance().getLocalIP() + ":" + frontEnd;
				String top = new String(stop.recv());
				String msg = new String(stop.recv());
				logger.info("cvoicu: echAddress="+exchAddress+";msg="+msg);
				if(msg.compareTo(exchAddress) == 0) {
					logger.info("cvoicu: msg equal to exchAddress");
					//double error = 1.0/0.0;
					//cvoicu: close all log files when exchange shut down.
					Collection<TopicBuffer> tbs = this.ml.getMessageHandler().getExchangeBuffer().values();
					Iterator<TopicBuffer> iterTBS = tbs.iterator();
					while(iterTBS.hasNext()) {
						iterTBS.next().getLogManagement().closeFiles();
					}
					System.exit(1);
				}
			}
		}
		closeSockets();
		exchStatTimer.shutDown();
		heartBeatTimer.shutDown();
		timer.purge();
		timer.cancel();
		logger.info("Stopping Exchange "+frontEnd+"->"+backEnd);
	}
	
	/**
	 * Optimized decoding of strings.
	 *  @param bytes the encoded byte array
	 * @return the decoded string
	 */
	public String bytesToStringUTFCustom(byte[] bytes) {
		char[] buffer = new char[bytes.length >> 1];
		for (int i = 0; i < buffer.length; i++) {
			int bpos = i << 1;
			char c = (char) (((bytes[bpos] & 0x00FF) << 8) + (bytes[bpos + 1] & 0x00FF));
			buffer[i] = c;
		}
		return new String(buffer);
	}


	/**
	 * Closes all sockets
	 */
	private void closeSockets() {
		logger.info("Closing all sockets from Exchange");
		frontendSub.close();
		backendPub.close();
		monitorPub.close();

	}

	/**
	 * @return the s_monitor
	 */
	public String getS_monitor() {
		return s_monitor;
	}

	/**
	 * @param s_monitor the s_monitor to set
	 */
	public void setS_monitor(String s_monitor) {
		this.s_monitor = s_monitor;
	}

	/**
	 * @return the knownProd
	 */
	public  synchronized HashMap<String, ProducerState> getKnownProd() {
		return knownProd;
	}

	/**
	 * @see org.roqmessaging.core.interfaces.IStoppable#shutDown()
	 */
	public void shutDown() {
		logger.info("Inititating shutdown sequence");
		this.active = false;
		this.timer.cancel();
		this.timer.purge();
		try {
			if(!this.monitorPub.send((new Integer(RoQConstant.EVENT_EXCHANGE_SHUT_DONW).toString()+",shutdown").getBytes(), 0))
				logger.error("Error when sending Exchange shut down", new IllegalStateException("Shut down Exchange notification not sent"));
		} catch (Exception e) {
			logger.warn("The socket is not available anymore. This happens when  the monitor has shut down.");
		}
		
	}

	/**
	 * @see org.roqmessaging.core.interfaces.IStoppable#getName()
	 */
	public String getName() {
		return "Exchange "+frontEnd+"->" + backEnd;
	}

	/**
	 * @return the iD
	 */
	public String getID() {
		return ID;
	}


}
