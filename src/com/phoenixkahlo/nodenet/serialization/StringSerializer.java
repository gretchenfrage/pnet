package com.phoenixkahlo.nodenet.serialization;

import java.io.IOException;
import java.io.OutputStream;

public class StringSerializer implements Serializer {

	@Override
	public boolean canSerialize(Object object) {
		return object != null && object.getClass() == String.class;
	}

	@Override
	public void serialize(Object object, OutputStream out) throws IOException {
		if (!canSerialize(object))
			throw new IllegalArgumentException(object + " isn't a string");
		byte[] bin = SerializationUtils.stringToBytes((String) object);
		SerializationUtils.serializeByteArray(bin, out);
	}

	@Override
	public Deserializer toDeserializer() {
		return new StringDeserializer();
	}

}
