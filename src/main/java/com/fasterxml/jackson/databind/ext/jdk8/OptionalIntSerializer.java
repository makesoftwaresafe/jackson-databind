package com.fasterxml.jackson.databind.ext.jdk8;

import java.util.OptionalInt;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper;
import com.fasterxml.jackson.databind.jsonFormatVisitors.JsonIntegerFormatVisitor;
import com.fasterxml.jackson.databind.ser.std.StdScalarSerializer;

public class OptionalIntSerializer extends StdScalarSerializer<OptionalInt>
{
    public OptionalIntSerializer() {
        super(OptionalInt.class);
    }

    @Override
    public boolean isEmpty(SerializerProvider provider, OptionalInt value) {
        return (value == null) || !value.isPresent();
    }

    @Override
    public void acceptJsonFormatVisitor(JsonFormatVisitorWrapper visitor,
            JavaType typeHint)
    {
        JsonIntegerFormatVisitor v2 = visitor
                .expectIntegerFormat(typeHint);
        if (v2 != null) {
            v2.numberType(JsonParser.NumberType.INT);
        }
    }
    
    @Override
    public void serialize(OptionalInt value, JsonGenerator gen, SerializerProvider provider)
        throws JacksonException
    {
        if (value.isPresent()) {
            gen.writeNumber(value.getAsInt());
        } else {
            gen.writeNull();
        }
    }
}
