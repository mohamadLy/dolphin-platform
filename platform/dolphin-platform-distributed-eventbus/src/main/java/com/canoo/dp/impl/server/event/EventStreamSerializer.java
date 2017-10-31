package com.canoo.dp.impl.server.event;

import com.canoo.dp.impl.platform.core.Assert;
import com.canoo.platform.remoting.server.event.MessageEventContext;
import com.canoo.platform.remoting.server.event.Topic;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.StreamSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;

import static com.canoo.dp.impl.server.event.DistributedEventConstants.CONTEXT_PARAM;
import static com.canoo.dp.impl.server.event.DistributedEventConstants.DATA_PARAM;
import static com.canoo.dp.impl.server.event.DistributedEventConstants.METADATA_KEY_PARAM;
import static com.canoo.dp.impl.server.event.DistributedEventConstants.METADATA_PARAM;
import static com.canoo.dp.impl.server.event.DistributedEventConstants.METADATA_VALUE_PARAM;
import static com.canoo.dp.impl.server.event.DistributedEventConstants.SPEC_1_0;
import static com.canoo.dp.impl.server.event.DistributedEventConstants.SPEC_VERSION_PARAM;
import static com.canoo.dp.impl.server.event.DistributedEventConstants.TIMESTAMP_PARAM;
import static com.canoo.dp.impl.server.event.DistributedEventConstants.TOPIC_PARAM;
import static com.canoo.dp.impl.server.event.DistributedEventConstants.TYPE_ID;

public class EventStreamSerializer implements StreamSerializer<DolphinEvent<?>> {

    private final Gson gson;

    public EventStreamSerializer(final Gson gson) {
        this.gson = Assert.requireNonNull(gson, "gson");
    }

    @Override
    public void write(ObjectDataOutput out, DolphinEvent<?> event) throws IOException {
        out.writeUTF(gson.toJson(convertToJson(event)));
    }

    private String toBase64(Serializable data) throws IOException {
        final ByteArrayOutputStream rawOutputStream = new ByteArrayOutputStream();
        final ObjectOutputStream dataOutputStream = new ObjectOutputStream(rawOutputStream);
        dataOutputStream.writeObject(data);
        Base64.Encoder encoder = Base64.getEncoder();
        return encoder.encodeToString(rawOutputStream.toByteArray());
    }

    private Serializable fromBase64(String data) throws IOException, ClassNotFoundException {
        final Base64.Decoder decoder = Base64.getDecoder();
        final byte[] raw = decoder.decode(data);
        final ByteArrayInputStream rawInputStream = new ByteArrayInputStream(raw);
        final ObjectInputStream dataInputStream = new ObjectInputStream(rawInputStream);
        return (Serializable) dataInputStream.readObject();
    }

    private JsonObject convertToJson(final DolphinEvent<?> event) throws IOException {
        final JsonObject root = new JsonObject();

        root.addProperty(SPEC_VERSION_PARAM, SPEC_1_0);

        final Serializable data = event.getData();
        if (data != null) {
            root.addProperty(DATA_PARAM, toBase64(data));
        } else {
            root.add(DATA_PARAM, JsonNull.INSTANCE);
        }

        root.add(CONTEXT_PARAM, convertToJson(event.getMessageEventContext()));

        return root;
    }

    private JsonObject convertToJson(final MessageEventContext<?> eventContext) throws IOException {
        final JsonObject root = new JsonObject();
        root.addProperty(TOPIC_PARAM, eventContext.getTopic().getName());
        root.addProperty(TIMESTAMP_PARAM, eventContext.getTimestamp());

        final JsonArray metadataArray = new JsonArray();
        for (Map.Entry<String, Serializable> entry : eventContext.getMetadata().entrySet()) {
            final String key = entry.getKey();
            final Serializable data = entry.getValue();
            final JsonObject metadataEntry = new JsonObject();
            metadataEntry.addProperty(METADATA_KEY_PARAM, key);
            if (data != null) {
                metadataEntry.addProperty(METADATA_VALUE_PARAM, toBase64(data));
            } else {
                metadataEntry.add(METADATA_VALUE_PARAM, JsonNull.INSTANCE);
            }
            metadataArray.add(metadataEntry);
        }
        root.add(METADATA_PARAM, metadataArray);
        return root;
    }

    @Override
    public DolphinEvent<?> read(ObjectDataInput in) throws IOException {
        final JsonElement root = new JsonParser().parse(in.readUTF());
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("Input can not be parsed!");
        }
        if (!root.getAsJsonObject().has(CONTEXT_PARAM)) {
            throw new IllegalArgumentException("Input can not be parsed! No event context found");
        }
        final JsonElement contextElement = root.getAsJsonObject().get(CONTEXT_PARAM);
        if (!contextElement.isJsonObject()) {
            throw new IllegalArgumentException("Input can not be parsed! event context not parseable");
        }

        if (!contextElement.getAsJsonObject().has(SPEC_VERSION_PARAM) &&
                contextElement.getAsJsonObject().get(SPEC_VERSION_PARAM).isJsonPrimitive() &&
                contextElement.getAsJsonObject().get(SPEC_VERSION_PARAM).getAsJsonPrimitive().isString() &&
                contextElement.getAsJsonObject().get(SPEC_VERSION_PARAM).getAsJsonPrimitive().getAsString().equals(SPEC_1_0)) {
            throw new IllegalArgumentException("Input can not be parsed! Unknown Spec");
        }

        if (!contextElement.getAsJsonObject().has(TOPIC_PARAM) &&
                contextElement.getAsJsonObject().get(TOPIC_PARAM).isJsonPrimitive() &&
                contextElement.getAsJsonObject().get(TOPIC_PARAM).getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Input can not be parsed! No topic found");
        }
        final Topic<?> topic = new Topic<>(contextElement.getAsJsonObject().getAsJsonPrimitive(TOPIC_PARAM).getAsString());


        if (!contextElement.getAsJsonObject().has(TIMESTAMP_PARAM) &&
                contextElement.getAsJsonObject().get(TIMESTAMP_PARAM).isJsonPrimitive() &&
                contextElement.getAsJsonObject().get(TIMESTAMP_PARAM).getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Input can not be parsed! No timestamp found");
        }
        final long timestamp = contextElement.getAsJsonObject().getAsJsonPrimitive(TIMESTAMP_PARAM).getAsLong();


        if (!contextElement.getAsJsonObject().has(DATA_PARAM)) {
            throw new IllegalArgumentException("Input can not be parsed! No data found");
        }
        final JsonElement dataElement = contextElement.getAsJsonObject().get(DATA_PARAM);
        Serializable data;
        if (dataElement.isJsonNull()) {
            data = null;
        } else if (dataElement.isJsonPrimitive() && dataElement.getAsJsonPrimitive().isString()) {
            try {
                data = fromBase64(dataElement.getAsJsonPrimitive().getAsString());
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("Input can not be parsed! Data can not be parsed");
            }
        } else {
            throw new IllegalArgumentException("Input can not be parsed! Data can not be parsed");
        }


        final DolphinEvent event = new DolphinEvent(topic, timestamp, data);


        if (!contextElement.getAsJsonObject().has(METADATA_PARAM)) {
            throw new IllegalArgumentException("Input can not be parsed! No metadata found");
        }
        final JsonElement metadataArrayElement = contextElement.getAsJsonObject().get(METADATA_PARAM);
        if (!metadataArrayElement.isJsonArray()) {
            throw new IllegalArgumentException("Input can not be parsed! metadata can not be parsed");
        }
        final Iterator<JsonElement> metadataIterator = metadataArrayElement.getAsJsonArray().iterator();
        while (metadataIterator.hasNext()) {
            final JsonElement metadataElement = metadataIterator.next();
            if (!metadataElement.isJsonObject()) {
                throw new IllegalArgumentException("Input can not be parsed! metadata can not be parsed");
            }
            if (!metadataElement.getAsJsonObject().has(METADATA_KEY_PARAM) &&
                    !metadataElement.getAsJsonObject().get(METADATA_KEY_PARAM).isJsonPrimitive() &&
                    !metadataElement.getAsJsonObject().get(METADATA_KEY_PARAM).getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("Input can not be parsed! metadata can not be parsed");
            }
            final String metadataKey = metadataElement.getAsJsonObject().get(METADATA_KEY_PARAM).getAsJsonPrimitive().getAsString();


            if (!metadataElement.getAsJsonObject().has(METADATA_VALUE_PARAM) &&
                    (!metadataElement.getAsJsonObject().get(METADATA_VALUE_PARAM).isJsonPrimitive() &&
                            !metadataElement.getAsJsonObject().get(METADATA_VALUE_PARAM).getAsJsonPrimitive().isString()) || !metadataElement.getAsJsonObject().get(METADATA_VALUE_PARAM).isJsonNull()) {
                throw new IllegalArgumentException("Input can not be parsed! metadata for key '" + metadataKey + "' can not be parsed");
            }
            if (metadataElement.getAsJsonObject().get(METADATA_VALUE_PARAM).isJsonNull()) {
                event.addMetadata(metadataKey, null);
            } else {
                try {
                    event.addMetadata(metadataKey, fromBase64(metadataElement.getAsJsonObject().get(METADATA_VALUE_PARAM).getAsJsonPrimitive().getAsString()));
                } catch (ClassNotFoundException e) {
                    throw new IllegalArgumentException("Input can not be parsed! metadata for key '" + metadataKey + "' can not be parsed");
                }
            }
        }
        return event;
    }

    @Override
    public int getTypeId() {
        return TYPE_ID;
    }

    @Override
    public void destroy() {

    }
}