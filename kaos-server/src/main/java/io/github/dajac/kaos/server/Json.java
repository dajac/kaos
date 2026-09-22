package io.github.dajac.kaos.server;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.github.dajac.kaos.Fault;
import io.github.dajac.kaos.Protocol;

/**
 * The JSON mapping of the HTTP API.
 *
 * <ul>
 *   <li>An unknown property, a {@code null} element of a list, a {@code null} for a primitive, a
 *       number for an enum, a fraction for an integer and trailing content are refused, so that a
 *       request body either binds exactly or becomes a 400.</li>
 *   <li>The {@code Optional} components of {@code ConsumerOverrides} bind through the jdk8 module,
 *       and an empty one is left out of the output ({@code NON_ABSENT}).</li>
 *   <li>A {@link Protocol} is written as its lower-case {@link Protocol#id() id}, also as a map key,
 *       and read in any case. A {@link Fault} is written as its {@link Fault#id() id}.</li>
 * </ul>
 */
final class Json {

    private Json() {}

    /** Creates the mapper of the HTTP API. */
    static ObjectMapper mapper() {
        var module = new SimpleModule("kaos-http")
            .addSerializer(Protocol.class, new ProtocolSerializer())
            .addKeySerializer(Protocol.class, new ProtocolKeySerializer())
            .addDeserializer(Protocol.class, new ProtocolDeserializer())
            .addSerializer(Fault.class, new FaultSerializer());
        var mapper = JsonMapper.builder()
            .addModule(new Jdk8Module())
            .addModule(module)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .serializationInclusion(JsonInclude.Include.NON_ABSENT)
            .build();
        mapper.configOverride(List.class).setSetterInfo(JsonSetter.Value.forContentNulls(Nulls.FAIL));
        return mapper;
    }

    private static final class ProtocolSerializer extends JsonSerializer<Protocol> {
        @Override
        public void serialize(Protocol value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(value.id());
        }
    }

    private static final class ProtocolKeySerializer extends JsonSerializer<Protocol> {
        @Override
        public void serialize(Protocol value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeFieldName(value.id());
        }
    }

    private static final class ProtocolDeserializer extends JsonDeserializer<Protocol> {
        @Override
        public Protocol deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                return (Protocol) context.handleUnexpectedToken(Protocol.class, parser);
            }
            var text = parser.getText();
            try {
                return Protocol.of(text);
            } catch (IllegalArgumentException e) {
                throw context.weirdStringException(text, Protocol.class, "expected consumer or classic");
            }
        }
    }

    private static final class FaultSerializer extends JsonSerializer<Fault> {
        @Override
        public void serialize(Fault value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(value.id());
        }
    }
}
