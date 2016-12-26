package com.phoenixkahlo.nodenet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.phoenixkahlo.nodenet.serialization.NullableSerializer;
import com.phoenixkahlo.nodenet.serialization.Serializer;
import com.phoenixkahlo.nodenet.stream.DatagramStream;
import com.phoenixkahlo.nodenet.stream.DisconnectionException;
import com.phoenixkahlo.nodenet.stream.ObjectStream;
import com.phoenixkahlo.nodenet.stream.SerializerObjectStream;

/**
 * An object owned by a LocalNode to handle the AddressedMessage system. When a
 * new connection is formed, the LocalNode sends it to the HandshakeHandler to
 * set it up and return a node if it succeeds in setup.
 */
public class HandshakeHandler {

	private Serializer serializer;
	private NodeAddress localAddress;
	private NetworkModel model;
	private Map<NodeAddress, ObjectStream> connections;
	private Map<NodeAddress, ChildNode> nodes;
	private ViralMessageHandler viralHandler;
	private AddressedMessageHandler addressedHandler;

	private List<Consumer<Node>> joinListeners = new ArrayList<>();
	private List<Consumer<Node>> leaveListeners = new ArrayList<>();

	public HandshakeHandler(Serializer serializer, NodeAddress localAddress, NetworkModel model,
			Map<NodeAddress, ObjectStream> connections, Map<NodeAddress, ChildNode> nodes,
			ViralMessageHandler viralHandler, AddressedMessageHandler addressedHandler) {
		super();
		this.serializer = serializer;
		this.localAddress = localAddress;
		this.model = model;
		this.connections = connections;
		this.nodes = nodes;
		this.viralHandler = viralHandler;
		this.addressedHandler = addressedHandler;
	}

	public Optional<Node> setup(DatagramStream connection) {
		ObjectStream stream = new SerializerObjectStream(connection, new NullableSerializer(serializer));

		Handshake received;
		try {
			stream.send(new Handshake(localAddress));
			received = stream.receive(Handshake.class);
		} catch (ProtocolViolationException | DisconnectionException e) {
			return Optional.empty();
		}
		NodeAddress remoteAddress = received.getSenderAddress();

		boolean alreadyConnected = model.connected(localAddress, remoteAddress);

		synchronized (model) {
			model.connect(localAddress, remoteAddress);
		}

		synchronized (connections) {
			connections.put(remoteAddress, stream);
		}

		ChildNode node = new ChildNode(addressedHandler, connections, localAddress, remoteAddress);
		synchronized (nodes) {
			nodes.put(remoteAddress, node);
		}

		viralHandler.transmit(new NeighborSetUpdateTrigger());

		new StreamReceiverThread(stream, remoteAddress, addressedHandler, viralHandler).start();

		if (!alreadyConnected) {
			synchronized (joinListeners) {
				joinListeners.forEach(listener -> listener.accept(node));
			}
		}

		stream.setDisconnectHandler(() -> {
			synchronized (model) {
				model.disconnect(localAddress, remoteAddress);
			}
			synchronized (connections) {
				connections.remove(remoteAddress);
			}
			synchronized (connections) {
				viralHandler.transmit(new NeighborSetUpdate(localAddress, new HashSet<>(connections.keySet())));
			}
			List<NodeAddress> addresses;
			synchronized (nodes) {
				addresses = nodes.keySet().stream().collect(Collectors.toList());
			}
			synchronized (model) {
				addresses.removeIf(address -> model.connected(localAddress, address));
			}
			synchronized (nodes) {
				addresses.stream().map(nodes::get)
						.forEach(left -> leaveListeners.forEach(listener -> listener.accept(left)));
			}
		});

		return Optional.of(node);
	}
	
	public void addJoinListener(Consumer<Node> listener) {
		synchronized (joinListeners) {
			joinListeners.add(listener);
		}
	}
	
	public void addLeaveListener(Consumer<Node> listener) {
		synchronized (leaveListeners) {
			leaveListeners.add(listener);
		}
	}
	
	public void removeJoinListener(Consumer<Node> listener) {
		synchronized (joinListeners) {
			joinListeners.remove(listener);
		}
	}
	
	public void removeLeaveListener(Consumer<Node> listener) {
		synchronized (leaveListeners) {
			leaveListeners.remove(listener);
		}
	}

}