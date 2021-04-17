/* 
 * Copyright 2010 Aalto University, ComNet
 *Edited by Sibusiso Shabalala (to make it Energy Efficient Routing Protocol) 
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;


import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import routing.util.EnergyModel;

import routing.util.RoutingInfo;

import util.Tuple;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;

/**
 * Implementation of PRoPHET router as described in 
 * <I>Probabilistic routing in intermittently connected networks</I> by
 * Anders Lindgren et al.
 */
public class E_ProphetRouter extends ActiveRouter {
	/** delivery predictability initialization constant*/
	public Map<String, Integer> delivered; // msg ID, source ID and destination ID
	private static double threshold;
	private static double battery_level_threshold; // Minimum battery power
	private static int transFactor; //transmission factor
	/**
	* delivery predictability initialization constant
	*/
	
	public static final double P_INIT = 0.75;
	/** delivery predictability transitivity scaling constant default value */
	public static final double DEFAULT_BETA = 0.25;
	/** delivery predictability aging constant */
	public static final double GAMMA = 0.98;
	
	/** Prophet router's setting namespace ({@value})*/ 
	public static final String PROPHET_NS = "E_ProphetRouter";
	/**
	 * Number of seconds in time unit -setting id ({@value}).
	 * How many seconds one time unit is when calculating aging of 
	 * delivery predictions. Should be tweaked for the scenario.*/
	public static final String SECONDS_IN_UNIT_S ="secondsInTimeUnit";
	
	/**
	 * Transitivity scaling constant (beta) -setting id ({@value}).
	 * Default value for setting is {@link #DEFAULT_BETA}.
	 */
	public static final String BETA_S = "beta";

	/** the value of nrof seconds in time unit -setting */
	private int secondsInTimeUnit;
	/** value of beta setting */
	private double beta;

	/** delivery predictabilities */
	private Map<DTNHost, Double> preds;
	/** last delivery predictability update (sim)time */
	private double lastAgeUpdate;
	
	
	
	/**
	* Constructor. Creates a new message router based on the settings in the
	* given Settings object.
	*
	* @param s The settings object
	*/
	static {
	Settings s = new Settings();
	threshold = s.getDouble("E_ProphetRouter.threshold");
	battery_level_threshold = s.getInt("E_ProphetRouter.transFactor");
	transFactor = s.getInt("E_ProphetRouter.transmissionFactor");
	
	}

	
	/**
	 * Constructor. Creates a new message router based on the settings in
	 * the given Settings object.
	 * @param s The settings object
	 */
	public E_ProphetRouter(Settings s) {
		super(s);
		Settings prophetSettings = new Settings(PROPHET_NS);
		secondsInTimeUnit = prophetSettings.getInt(SECONDS_IN_UNIT_S);
		if (prophetSettings.contains(BETA_S)) {
			beta = prophetSettings.getDouble(BETA_S);
		}
		else {
			beta = DEFAULT_BETA;
		}

		initPreds();
	}

	/**
	 * Copyconstructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected E_ProphetRouter(E_ProphetRouter r) {
		super(r);
		this.secondsInTimeUnit = r.secondsInTimeUnit;
		this.beta = r.beta;
		initPreds();
		initDelivered();
	}
	
	/**
	 * Initializes predictability hash
	 */
	private void initPreds() {
		this.preds = new HashMap<DTNHost, Double>();
	}

	/**
	* Initializes Delivered hash
	*/
	private void initDelivered() {
	this.delivered = new HashMap<>(200);
	}
	
	@Override
	public void changedConnection(Connection con) {
		super.changedConnection(con);
		
		if (con.isUp()) {
			DTNHost otherHost = con.getOtherNode(getHost());
			updateDeliveryPredFor(otherHost);
			updateTransitivePreds(otherHost);
		}
	}
	
	/**
	 * Updates delivery predictions for a host.
	 * <CODE>P(a,b) = P(a,b)_old + (1 - P(a,b)_old) * P_INIT</CODE>
	 * @param host The host we just met
	 */
	private void updateDeliveryPredFor(DTNHost host) {
		double oldValue = getPredFor(host);
		double newValue = oldValue + (1 - oldValue) * P_INIT;
		preds.put(host, newValue);
	}
	
	/**
	 * Returns the current prediction (P) value for a host or 0 if entry for
	 * the host doesn't exist.
	 * @param host The host to look the P for
	 * @return the current P value
	 */
	public double getPredFor(DTNHost host) {
		ageDeliveryPreds(); // make sure preds are updated before getting
		if (preds.containsKey(host)) {
			return preds.get(host);
		}
		else {
			return 0;
		}
	}
	
	/**
	 * Updates transitive (A->B->C) delivery predictions.
	 * <CODE>P(a,c) = P(a,c)_old + (1 - P(a,c)_old) * P(a,b) * P(b,c) * BETA
	 * </CODE>
	 * @param host The B host who we just met
	 */
	private void updateTransitivePreds(DTNHost host) {
		MessageRouter otherRouter = host.getRouter();
		assert otherRouter instanceof E_ProphetRouter : "PRoPHET only works " + 
			" with other routers of same type";
		
		double pForHost = getPredFor(host); // P(a,b)
		Map<DTNHost, Double> othersPreds = 
			((E_ProphetRouter)otherRouter).getDeliveryPreds();
		
		for (Map.Entry<DTNHost, Double> e : othersPreds.entrySet()) {
			if (e.getKey() == getHost()) {
				continue; // don't add yourself
			}
			
			double pOld = getPredFor(e.getKey()); // P(a,c)_old
			double pNew = pOld + ( 1 - pOld) * pForHost * e.getValue() * beta;
			preds.put(e.getKey(), pNew);
		}
	}

	/**
	 * Ages all entries in the delivery predictions.
	 * <CODE>P(a,b) = P(a,b)_old * (GAMMA ^ k)</CODE>, where k is number of
	 * time units that have elapsed since the last time the metric was aged.
	 * @see #SECONDS_IN_UNIT_S
	 */
	private void ageDeliveryPreds() {
		double timeDiff = (SimClock.getTime() - this.lastAgeUpdate) / 
			secondsInTimeUnit;
		
		if (timeDiff == 0) {
			return;
		}
		
		double mult = Math.pow(GAMMA, timeDiff);
		for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
			e.setValue(e.getValue()*mult);
		}
		
		this.lastAgeUpdate = SimClock.getTime();
	}
	
	/**
	 * Returns a map of this router's delivery predictions
	 * @return a map of this router's delivery predictions
	 */
	private Map<DTNHost, Double> getDeliveryPreds() {
		ageDeliveryPreds(); // make sure the aging is done
		return this.preds;
	}
	
	@Override
	public void update() {
		super.update();
		if (!canStartTransfer() ||isTransferring()) {
			return; // nothing to transfer or is currently transferring 
		}
		
		// try messages that could be delivered to final recipient
		if (exchangeDeliverableMessages() != null) {
			return;
		}
		
		tryOtherMessages();		
	}
	
	/**
	 * Tries to send all other messages to all connected hosts ordered by
	 * their delivery probability
	 * @return The return value of {@link #tryMessagesForConnected(List)}
	 */
	private Tuple<Message, Connection> tryOtherMessages() {
		List<Tuple<Message, Connection>> messages = 
			new ArrayList<Tuple<Message, Connection>>(); 
	
		Collection<Message> msgCollection = getMessageCollection();
		Collection<Message> msg_to_be_deleted = new HashSet<Message>();
		/* for all connected hosts collect all messages that have a higher
		   probability of delivery by the other host */
		for (Connection con : getConnections()) {
			DTNHost other = con.getOtherNode(getHost());
			E_ProphetRouter othRouter = (E_ProphetRouter)other.getRouter();
			
			if (othRouter.isTransferring()) {
				continue; // skip hosts that are transferring
			}
			
			for (Message m : msgCollection) {
				if (othRouter.hasMessage(m.getId())) {
					continue; // skip messages that the other one has
				}
				
				double curr_energy = (double) othRouter.getHost().getComBus().getProperty(EnergyModel.ENERGY_VALUE_ID);
				DTNHost dest = m.getTo(); //creat the object of the destination of the msg to contain destination ID
				String key = m.getId() + "<->" + m.getFrom().toString() + "<->" + dest.toString(); ///sbu, we are getting the msg ID, Source ID and the destinatio ID od the current msg (Ack_table)
				if (othRouter.delivered.containsKey(key)) {
					int cnt = (int) othRouter.delivered.get(key);
					this.delivered.put(key, ++cnt);///update Ack_Table
					msg_to_be_deleted.add(m);//delate the msg
					continue;
					}
				// if energy of neighbour host is less than the battery level threshold and neighbour host is not the destination node
				if (curr_energy < this.battery_level_threshold && !dest.equals(other)) {
				continue;
				}
				
				//if other node is destination node then Increase computional performance and save energy by skipping metric computions
				
				if (othRouter.getPredFor(m.getTo()) > getPredFor(m.getTo())) {
					// the other node has higher probability of delivery
					messages.add(new Tuple<Message, Connection>(m,con));
					this.delivered.put(key, 1);
				}
			}			
		}
		
		if (messages.size() == 0) {
			return null;
		}
		
		if (msg_to_be_deleted.size() > 0) {
			for (Message m : msg_to_be_deleted) {
			this.deleteMessage(m.getId(), false);
			}
			
			try {
				Collections.sort(messages, new TupleComparator());
				} catch (Exception Ex) {
				}
				return tryMessagesForConnected(messages);
				}
				// sort the message-connection tuples
				Collections.sort(messages, new TupleComparator());
				return tryMessagesForConnected(messages); // try to send messages
				}		
			
		
	
	/**
	 * Comparator for Message-Connection-Tuples that orders the tuples by
	 * their delivery probability by the host on the other side of the 
	 * connection (GRTRMax)
	 */
	private class TupleComparator implements Comparator 
		<Tuple<Message, Connection>> {

		public int compare(Tuple<Message, Connection> tuple1,
				Tuple<Message, Connection> tuple2) {
			// delivery probability of tuple1's message with tuple1's connection
			double p1 = ((E_ProphetRouter)tuple1.getValue().
					getOtherNode(getHost()).getRouter()).getPredFor(
					tuple1.getKey().getTo());
			// -"- tuple2...
			double p2 = ((E_ProphetRouter)tuple2.getValue().
					getOtherNode(getHost()).getRouter()).getPredFor(
					tuple2.getKey().getTo());

			// bigger probability should come first
			if (p2-p1 == 0) {
				/* equal probabilities -> let queue mode decide */
				return compareByQueueMode(tuple1.getKey(), tuple2.getKey());
			}
			else if (p2-p1 < 0) {
				return -1;
			}
			else {
				return 1;
			}
		}
	}
	
	
	
	//==================Recieve the msg============================
	@Override
	public int receiveMessage(Message m, DTNHost from) {
	if (m.getSize() == -1) { ///-1 represent ack_M if the msg size is -1, there if it ack_M, do the following
	String ack_m = m.getId(); //we create the object to contan msg ID
	this.delivered.put(ack_m, 1); // we put the msd ID to ack_table
	String[] parts = ack_m.split("<->");
	String m_Id = parts[0];
	this.deleteMessage(m_Id, true); // Delete with that ID
	return 0;
	}
	int i = super.receiveMessage(m, from);
	if (m.getTo().equals(this.getHost()) && i == RCV_OK) {
	String ack_m = m.getId() + "<->" + m.getFrom().toString() + "<->" + m.getTo().toString(); //delear new ack_m to be sent to the last sender
	Message ack_mes = new Message(this.getHost(), from, ack_m, -1); //creating ack_m with the size -1
	from.receiveMessage(ack_mes, this.getHost()); //get the host for the last sender
	this.delivered.put(ack_m, 1); // send the ack_m to the last sender
	}
	return i;
	}
	@Override
	public RoutingInfo getRoutingInfo() {
		ageDeliveryPreds();
		RoutingInfo top = super.getRoutingInfo();
		RoutingInfo ri = new RoutingInfo(preds.size() + 
				" delivery prediction(s)");
		
		for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
			DTNHost host = e.getKey();
			Double value = e.getValue();
			
			ri.addMoreInfo(new RoutingInfo(String.format("%s : %.6f", 
					host, value)));
		}
		
		top.addMoreInfo(ri);
		return top;
	}
	
	@Override
	public MessageRouter replicate() {
		E_ProphetRouter r = new E_ProphetRouter(this);
		return r;
	}

}
