package com.phoenixkahlo.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ThreadLocalRandom;

import com.phoenixkahlo.nodenet.serialization.FieldSerializer;
import com.phoenixkahlo.nodenet.serialization.SerializationUtils;
import com.phoenixkahlo.nodenet.serialization.Serializer;

public class UUID {

	public static Serializer serializer() {
		return new FieldSerializer(UUID.class, UUID::new);
	}
	
	private long data1;
	private long data2;
	
	public UUID() {
		data1 = ThreadLocalRandom.current().nextLong();
		data2 = ThreadLocalRandom.current().nextLong();
	}
	
	public UUID(InputStream in) throws IOException {
		data1 = SerializationUtils.readLong(in);
		data2 = SerializationUtils.readLong(in);
	}
	
	public void write(OutputStream out) throws IOException {
		SerializationUtils.writeLong(data1, out);
		SerializationUtils.writeLong(data2, out);
	}
	
	@Override
	public boolean equals(Object other) {
		if (other instanceof UUID)
			return data1 == ((UUID) other).data1 && data2 == ((UUID) other).data2;
		else
			return false;
	}
	
}
