/**
 * 
 */
package no.hvl.dat110.middleware;

import java.math.BigInteger;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import no.hvl.dat110.rpc.interfaces.NodeInterface;
import no.hvl.dat110.util.LamportClock;
import no.hvl.dat110.util.Util;

/**
 * @author tdoy
 *
 */
public class MutualExclusion {
		
	private static final Logger logger = LogManager.getLogger(MutualExclusion.class);
	/** lock variables */
	private boolean CS_BUSY = false;						// indicate to be in critical section (accessing a shared resource) 
	private boolean WANTS_TO_ENTER_CS = false;				// indicate to want to enter CS
	private List<Message> queueack; 						// queue for acknowledged messages
	private List<Message> mutexqueue;						// queue for storing process that are denied permission. We really don't need this for quorum-protocol
	
	private LamportClock clock;								// lamport clock
	private Node node;
	
	public MutualExclusion(Node node) throws RemoteException {
		this.node = node;
		
		clock = new LamportClock();
		queueack = new ArrayList<Message>();
		mutexqueue = new ArrayList<Message>();
	}
	
	public synchronized void acquireLock() {
		CS_BUSY = true;
	}
	
	public void releaseLocks() {
		WANTS_TO_ENTER_CS = false;
		CS_BUSY = false;
	}

	public boolean doMutexRequest(Message message, byte[] updates) throws RemoteException {
		
		logger.info(node.nodename + " wants to access CS");
		// clear the queueack before requesting for votes
		queueack.clear();
		
		// clear the mutexqueue
		mutexqueue.clear();
		
		// increment clock
		clock.increment();
		
		// adjust the clock on the message, by calling the setClock on the message
		message.setClock(clock.getClock());
				
		// wants to access resource - set the appropriate lock variable
		WANTS_TO_ENTER_CS = true;
		
		// start MutualExclusion algorithm
		
		// first, call removeDuplicatePeersBeforeVoting. A peer can hold/contain 2 replicas of a file. This peer will appear twice
		List<Message> messages = removeDuplicatePeersBeforeVoting();

		// multicast the message to activenodes (hint: use multicastMessage)
		multicastMessage(message, messages);
		
		// check that all replicas have replied (permission)
		boolean permission = areAllMessagesReturned(messages.size());

		if (permission) {
			// if yes, acquireLock
			acquireLock();

			// node.broadcastUpdatetoPeers
			node.broadcastUpdatetoPeers(updates);

			// clear the mutexqueue
			mutexqueue.clear();
		}

		// return permission
		return permission;
	}
	
	// multicast message to other processes including self
	private void multicastMessage(Message message, List<Message> activenodes) throws RemoteException {
		
        logger.info("Number of peers to vote = "+activenodes.size());
		
		// iterate over the activenodes
		for (Message m : activenodes) {
			// obtain a stub for each node from the registry
			NodeInterface stub = Util.getProcessStub(m.getNodeName(), m.getPort());

			// call onMutexRequestReceived()
			if (stub != null) {
				stub.onMutexRequestReceived(message);
			}
		}
		
	}
	
	public void onMutexRequestReceived(Message message) throws RemoteException {
		
		// increment the local clock
				clock.increment();
				
				// if message is from self, acknowledge, and call onMutexAcknowledgementReceived()
				if (message.getNodeName().equals(node.getNodeName())) {
					message.setAcknowledged(true);
					onMutexAcknowledgementReceived(message);
				}
					
				int caseid = -1;
				
				/* write if statement to transition to the correct caseid */

				// caseid=0: Receiver is not accessing shared resource and does not want to (send OK to sender)
				if (!CS_BUSY && !WANTS_TO_ENTER_CS) {
					caseid = 0;

					// caseid=1: Receiver already has access to the resource (dont reply but queue the request)
				} else if (CS_BUSY) {
					caseid = 1;

					// caseid=2: Receiver wants to access resource but is yet to - compare own message clock to received message's clock
				} else if (WANTS_TO_ENTER_CS) {
					caseid = 2;
				}
				
				// check for decision
				doDecisionAlgorithm(message, mutexqueue, caseid);
			}
	
	public void doDecisionAlgorithm(Message message, List<Message> queue, int condition) throws RemoteException {
		
		String procName = message.getNodeName();
		int port = message.getPort();
		
		switch(condition) {
		
			/** case 1: Receiver is not accessing shared resource and does not want to (send OK to sender) */
			case 0: {
				
				// get a stub for the sender from the registry
				NodeInterface stub = Util.getProcessStub(procName, port);
				
				// acknowledge message
				message.setAcknowledged(true);
				
				// send acknowledgement back by calling onMutexAcknowledgementReceived()
				stub.onMutexAcknowledgementReceived(message);
				
				break;
			}
		
			/** case 2: Receiver already has access to the resource (dont reply but queue the request) */
			case 1: {
				
				// queue this message
				queue.add(message);

				break;
			}
			
			/**
			 *  case 3: Receiver wants to access resource but is yet to (compare own message clock to received message's clock
			 *  the message with lower timestamp wins) - send OK if received is lower. Queue message if received is higher
			 */
			case 2: {
				
				// check the clock of the sending process (note that the correct clock is in the message)
				int senderClock = message.getClock();
				
				// own clock for the multicast message (note that the correct clock is in the message)
				int ownClock = node.getMessage().getClock();
				
				// compare clocks, the lowest wins
				int compare = senderClock-ownClock;
				
				// if clocks are the same, compare nodeIDs, the lowest wins
				if (compare == 0) {
					compare = message.getNodeID().compareTo(node.getNodeID());
				}

				// if sender wins, acknowledge the message, obtain a stub and call onMutexAcknowledgementReceived()
				if (compare < 0) {
					message.setAcknowledged(true);
					NodeInterface stub = Util.getProcessStub(procName, port);
					if (stub != null) {
						stub.onMutexAcknowledgementReceived(message);
					}
				} else {
					// if sender looses, queue it
					queue.add(message);
				}

				break;
			}
			
			default: break;
		}
		
	}
	
	public void onMutexAcknowledgementReceived(Message message) throws RemoteException {
		
		// add message to queueack
		queueack.add(message);
	}
	
	// multicast release locks message to other processes including self
	public void multicastReleaseLocks(Set<Message> activenodes) {
        logger.info("Releasing locks from = "+activenodes.size());
		
		// iterate over the activenodes
		for (Message m : activenodes) {
			// obtain a stub for each node from the registry
			NodeInterface stub = Util.getProcessStub(m.getNodeName(), m.getPort());

			// call releaseLocks()
			try {
				if (stub != null) {
					stub.releaseLocks();
				}
			} catch (RemoteException e) {
				throw new RuntimeException(e);
			}
		}
	}
	
	private boolean areAllMessagesReturned(int numvoters) throws RemoteException {
        logger.info(node.getNodeName()+": size of queueack = "+queueack.size());
		
		// check if the size of the queueack is same as the numvoters
		boolean allReturned = queueack.size() == numvoters;

		// clear the queueack
		if (allReturned) {
			queueack.clear();
			return true;
		}
		
		// return true if yes and false if no
		return false;

	}
	
	private List<Message> removeDuplicatePeersBeforeVoting() {
		
		List<Message> uniquepeer = new ArrayList<Message>();
		for(Message p : node.activenodesforfile) {
			boolean found = false;
			for(Message p1 : uniquepeer) {
				if(p.getNodeName().equals(p1.getNodeName())) {
					found = true;
					break;
				}
			}
			if(!found)
				uniquepeer.add(p);
		}		
		return uniquepeer;
	}
}
