package com.phoenixkahlo.pnet;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.phoenixkahlo.pnet.serialization.NullableSerializer;
import com.phoenixkahlo.pnet.serialization.Serializer;
import com.phoenixkahlo.pnet.serialization.UnionSerializer;
import com.phoenixkahlo.pnet.socket.BasicStreamFamily;
import com.phoenixkahlo.pnet.socket.ObjectStream;
import com.phoenixkahlo.pnet.socket.DatagramStream;
import com.phoenixkahlo.pnet.socket.StreamFamily;
import com.phoenixkahlo.util.UnorderedTuple;

public class BasicNetworkConnection implements NetworkConnection {

	private UnionSerializer union; // Synchronize

	private final NodeAddress localAddress;
	private final StreamFamily socketFamily;
	private NetworkModel model; // Synchronize
	private Map<NodeAddress, ObjectStream> neighborConnections = new HashMap<>(); // Synchronize
	private Map<NodeAddress, BasicNetworkNode> nodeReifications = new HashMap<>(); // Synchronize
	private List<Consumer<NetworkNode>> newConnectionListeners = new ArrayList<>(); // Synchronize
	private Set<Integer> handledVirii = new HashSet<>();
	private ResourceWaiter<AddressedPayloadResult> addressedResults = new ResourceWaiter<>();
	private List<Thread> workerThreads = new ArrayList<>(); // Synchronize

	private BasicNetworkConnection(StreamFamily socketFamily) {
		union = new UnionSerializer();
		SerializerInitializer.init(union);

		this.localAddress = new NodeAddress(ThreadLocalRandom.current().nextInt());
		this.socketFamily = socketFamily;
		this.model = new NetworkModel(localAddress);

		socketFamily.setReceiveHandler(this::receiveSocketConnection);
	}

	public BasicNetworkConnection(int port) throws SocketException {
		this(new BasicStreamFamily(port));
	}

	public BasicNetworkConnection() throws SocketException {
		this(new BasicStreamFamily());
	}

	@Override
	public void addSerializer(Serializer serializer, int header) {
		if (header < 0)
			throw new IllegalArgumentException("Illegal header " + header + ", negative headers are reserved");
		synchronized (serializer) {
			this.union.add(header, serializer);
			neighborConnections.values().stream().forEach(ObjectStream::rebuildDeserializer);
		}
	}

	@Override
	public Optional<NetworkNode> connect(SocketAddress address) {
		// Attempt to form the DatagramStream
		Optional<DatagramStream> socket = socketFamily.connect(address);

		// Fail if DatagramStream form failed.
		if (!socket.isPresent())
			return Optional.empty();

		// Attempt to setup connection
		return setupConnection(socket.get());
	}

	/**
	 * Invoked by the underlying StreamFamily.
	 */
	private void receiveSocketConnection(DatagramStream socket) {
		Optional<NetworkNode> connection = setupConnection(socket);
		if (connection.isPresent()) {
			synchronized (newConnectionListeners) {
				newConnectionListeners.parallelStream().forEach(listener -> listener.accept(connection.get()));
			}
		}
	}

	/**
	 * Deal with the ViralPayload as described in the ViralPayload
	 * documentation.
	 */
	public void handleViralMessage(ViralPayload virus) {
		boolean shouldHandle = false;
		synchronized (handledVirii) {
			if (!handledVirii.contains(virus.getID())) {
				handledVirii.add(virus.getID());
				shouldHandle = true;
			}
		}
		if (shouldHandle) {
			virus.addInfected(localAddress);
			// Infect neighbors
			synchronized (neighborConnections) {
				neighborConnections.entrySet().parallelStream().forEach(neighbor -> {
					if (!virus.getInfected().contains(neighbor.getKey())) {
						try {
							neighbor.getValue().send(virus);
						} catch (IOException e) {
							System.err.println("IOException sending " + virus + " to " + neighbor.getKey());
							e.printStackTrace();
						}
					}
				});
			}
			// Deal with payload
			handleViralPayload(virus.getPayload());
		}
	}

	private void handleViralPayload(Object payload) {
		if (payload instanceof ConnectionNotification) {
			ConnectionNotification notification = (ConnectionNotification) payload;
			synchronized (model) {
				model.addConnection(notification.get1(), notification.get2());
			}
		} else if (payload instanceof DisconnectionNotification) {
			DisconnectionNotification notification = (DisconnectionNotification) payload;
			synchronized (model) {
				model.removeConnection(notification.get1(), notification.get2());
			}
		} else {
			System.err.println("Invalid viral payload type: " + payload);
		}
	}

	private void sendAddressedResult(NodeAddress to, int id, boolean success) {
		ObjectStream socket;
		synchronized (neighborConnections) {
			socket = neighborConnections.get(to);
		}
		if (socket == null) {
			System.err.println("Could not find socket to which to send addressed result.");
			return;
		}
		try {
			socket.send(new AddressedPayloadResult(id, success));
		} catch (IOException e) {
			System.err.println("IOException while sending addressed result:");
			e.printStackTrace();
		}
	}

	private void sendAddressedMessage(AddressedPayload message, NodeAddress to) {
		ObjectStream socket;
		synchronized (neighborConnections) {
			socket = neighborConnections.get(to);
		}
		if (socket == null) {
			System.err.println("Could not find socket to which to send addressed message.");
			return;
		}
		try {
			socket.send(message);
		} catch (IOException e) {
			System.err.println("IOException while sending addressed message:");
			e.printStackTrace();
		}
	}

	private void handleAddressedPayload(Object payload) {
		System.err.println("Invalid addressed payload type: " + payload);
	}

	private Iterator<NodeAddress> addressedAttemptSequence(NodeAddress destination, Set<NodeAddress> illegal) {
		// Prepare a model of the subset of the network that is legal to
		// transverse
		NetworkModel transversible;
		synchronized (model) {
			transversible = model.clone();
		}
		for (NodeAddress node : illegal) {
			transversible.removeNode(node);
		}
		transversible.removeNode(localAddress);
		// Form a SortedMap of existing distances, sorted by shortest distances
		SortedMap<Integer, NodeAddress> distances = new TreeMap<>((n1, n2) -> n1 - n2);
		synchronized (neighborConnections) {
			for (NodeAddress neighbor : neighborConnections.keySet()) {
				OptionalInt distance = transversible.getShortestDistance(neighbor, destination);
				if (distance.isPresent())
					distances.put(distance.getAsInt(), neighbor);
			}
		}
		// Return the value iterator
		return distances.values().iterator();
	}

	private long addressedPatience(NodeAddress attempt, NodeAddress destination) {
		// TODO: actually calculate something
		return 500;
	}

	/**
	 * Deal with the AddressedPayload as described in the AddressedPayload
	 * documentation.
	 */
	public void handleAddressedMessage(NodeAddress from, AddressedPayload addressed) {
		if (addressed.getDestination().equals(localAddress)) {
			sendAddressedResult(from, addressed.getOriginalID(), true);
			handleAddressedPayload(addressed.getPayload());
		} else {
			Thread forwarder = new Thread(() -> {
				// Parent thread
				Thread parent = Thread.currentThread();
				// List of children waiting on success/failure of particular
				// neighbor
				List<Thread> waiters = Collections.synchronizedList(new ArrayList<>());
				// List of neighbors that have succeeded
				List<Thread> successful = Collections.synchronizedList(new ArrayList<>());
				// Iterator for sequence of neigbors to attempt
				Iterator<NodeAddress> sequence = addressedAttemptSequence(addressed.getDestination(),
						addressed.getVisited());

				/*
				 * Sequentially attempt sending the message to each neighbor,
				 * with pauses in between, until either one of them succeeds,
				 * all of them fail, or all of them timeout. In the event that
				 * this thread is interrupted, stop all child threads.
				 */
				try {
					while (sequence.hasNext()) {
						// Get next neighbor in sequence
						NodeAddress attempt = sequence.next();
						// Send neighbor message
						addressed.randomizeID();
						sendAddressedMessage(addressed, attempt);
						// Launch waiter thread
						int resultID = addressed.getID();
						Thread waiter = new Thread(() -> {
							Optional<AddressedPayloadResult> result = addressedResults.tryGet(resultID);
							waiters.remove(Thread.currentThread());
							if (result.isPresent() && result.get().wasSuccessful()) {
								successful.add(Thread.currentThread());
								parent.interrupt();
							} else if (waiters.isEmpty()) {
								parent.interrupt();
							}
						});
						waiters.add(waiter);
						waiter.start();
						// Sleep for patience
						long patience = addressedPatience(attempt, addressed.getDestination());
						Thread.sleep(patience);
					}
					// All children will be given an ultra-generous 10 seconds to succeed.
					Thread.sleep(10_000);
				} catch (InterruptedException e) {
				} finally {
					synchronized (waiters) {
						waiters.stream().forEach(Thread::interrupt);
					}
				}

				sendAddressedResult(from, addressed.getOriginalID(), !successful.isEmpty());
			});
			synchronized (workerThreads) {
				workerThreads.add(forwarder);
				forwarder.start();
			}
		}
	}

	/**
	 * Invoked by each child of the underlying SocketFamily upon PNetSocket
	 * detection of disconnection.
	 */
	private void handleDisconnect(NodeAddress address) {
		// Update internal data structures
		synchronized (model) {
			model.removeConnection(localAddress, address);
			model.trim();
		}
		synchronized (neighborConnections) {
			neighborConnections.remove(address);
		}
		synchronized (nodeReifications) {
			nodeReifications.remove(address);
		}
		// Virally inform all nodes of disconnection
		handleViralMessage(new ViralPayload(new DisconnectionNotification(localAddress, address)));
	}

	/**
	 * Attempt to send handshake, receive handshake, update internal data
	 * structures, virally inform all nodes of new connection, and return the
	 * associated node.
	 */
	private Optional<NetworkNode> setupConnection(DatagramStream socket) {
		// Turn byte[] stream to Object stream.
		ObjectStream objectSocket = new ObjectStream(socket, new NullableSerializer(union));

		// Create and send our own handshake.
		Handshake sendHandshake = new Handshake(localAddress, new HashSet<>(model.getConnections()));
		try {
			objectSocket.send(sendHandshake);
		} catch (IOException e) {
			System.err.println("IOException while attempting to connect " + this);
			socket.disconnect();
			return Optional.empty();
		}

		// Attempt to receive handshake
		Object received;
		try {
			received = objectSocket.receive();
		} catch (ProtocolViolationException e) {
			System.err.println("ProtocolVioationException while attempting to connect " + this);
			socket.disconnect();
			return Optional.empty();
		}
		if (!(received instanceof Handshake)) {
			System.err.println("Handshake received is actually " + received + " at " + this);
			socket.disconnect();
			return Optional.empty();
		}
		Handshake receivedHandshake = (Handshake) received;

		// Update internal data structures with info of new connection
		NodeAddress connectedTo = receivedHandshake.getSenderAddress();
		// Update network model
		synchronized (model) {
			model.addConnection(localAddress, connectedTo);
			for (UnorderedTuple<NodeAddress> connection : receivedHandshake.getKnownConnections()) {
				model.addConnection(connection.get1(), connection.get2());
			}
			model.trim();
		}
		BasicNetworkNode node;
		// Add to node reifications
		synchronized (nodeReifications) {
			if (nodeReifications.containsKey(connectedTo)) {
				node = nodeReifications.get(connectedTo);
			} else {
				node = new BasicNetworkNode(this, connectedTo);
				nodeReifications.put(connectedTo, node);
			}
		}
		// Add to neighbor connections
		synchronized (neighborConnections) {
			neighborConnections.put(connectedTo, objectSocket);
		}
		// Setup disconnect handler
		socket.setDisconnectHandler(() -> handleDisconnect(connectedTo));

		// Virally inform all nodes of new connection
		handleViralMessage(new ViralPayload(new ConnectionNotification(localAddress, connectedTo)));

		// Return the created node
		return Optional.of(node);
	}

	@Override
	public void setIncomingTest(Predicate<PotentialConnection> test) {
		socketFamily.setReceiveTest(potential -> test.test(new PotentialConnection(potential.getAddress())));
	}

	@Override
	public List<NetworkNode> getNodes() {
		synchronized (model) {
			model.trim();
			return model.getNodes().stream().map(nodeReifications::get).collect(Collectors.toList());
		}
	}

	@Override
	public List<NetworkNode> getAdjacentNodes() {
		synchronized (neighborConnections) {
			return neighborConnections.keySet().stream().map(nodeReifications::get).collect(Collectors.toList());
		}
	}

	@Override
	public void disconnect() {
		socketFamily.close();
		synchronized (workerThreads) {
			workerThreads.forEach(Thread::interrupt);
		}
	}

	@Override
	public void listenForConnection(Consumer<NetworkNode> listener) {
		synchronized (newConnectionListeners) {
			newConnectionListeners.add(listener);
		}
	}

}
