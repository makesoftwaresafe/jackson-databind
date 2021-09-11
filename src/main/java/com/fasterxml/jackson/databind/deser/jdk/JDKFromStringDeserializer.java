package com.fasterxml.jackson.databind.deser.jdk;

import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.spi.FileSystemProvider;
import java.util.*;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.util.VersionUtil;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.std.FromStringDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.databind.util.ClassUtil;

/**
 * Base class for simple deserializers that serialize values from String
 * representation: this includes JSON Strings and other Scalar values that
 * can be coerced into text, like Numbers and Booleans).
 * Simple JSON String values are trimmed using {@link java.lang.String#trim}.
 * Partial deserializer implementation will try to first access current token as
 * a String, calls {@code _deserialize(String,DeserializationContext)} and
 * returns return value.
 * If this does not work (current token not a simple scalar type), attempts
 * are made so that:
 *<ul>
 * <li>Embedded values ({@link JsonToken#VALUE_EMBEDDED_OBJECT}) are returned as-is
 *    if they are of compatible type
 *  </li>
 * <li>Arrays may be "unwrapped" if (and only if) {@link DeserializationFeature#UNWRAP_SINGLE_VALUE_ARRAYS}
 *    is enabled, and array contains just a single scalar value that can be deserialized
 *    (for example, JSON Array with single JSON String element).
 *  </li>
 * </ul>
 *<p>
 * Special handling includes:
 * <ul>
 * <li>Null values ({@link JsonToken#VALUE_NULL}) are handled by returning value
 *   returned by {@link ValueDeserializer#getNullValue(DeserializationContext)}: default
 *   implementation simply returns Java `null` but this may be overridden.
 *  </li>
 * <li>Empty String (after trimming) will result in {@link #_deserializeFromEmptyString}
 *   getting called, and return value being returned as deserialization: default implementation
 *   simply returns `null`.
 *  </li>
 * </ul>
 */
public class JDKFromStringDeserializer
    extends FromStringDeserializer<Object>
{
    public final static int STD_FILE = 1;
    public final static int STD_URL = 2;
    public final static int STD_URI = 3;
    public final static int STD_PATH = 4;
    public final static int STD_CLASS = 5;
    public final static int STD_JAVA_TYPE = 6;
    public final static int STD_CURRENCY = 7;
    public final static int STD_PATTERN = 8;
    public final static int STD_LOCALE = 9;
    public final static int STD_CHARSET = 10;
    public final static int STD_TIME_ZONE = 11;
    public final static int STD_INET_ADDRESS = 12;
    public final static int STD_INET_SOCKET_ADDRESS = 13;

    public static Class<?>[] types() {
        return new Class<?>[] {
            File.class,
            URL.class,
            URI.class,
            Path.class, // since 3.0
            Class.class,
            JavaType.class,
            Currency.class,
            Pattern.class,
            Locale.class,
            Charset.class,
            TimeZone.class,
            InetAddress.class,
            InetSocketAddress.class,

            // Special impl:
            StringBuilder.class,
        };
    }

    protected final static String LOCALE_EXT_MARKER = "_#";

    protected final int _kind;

    /*
    /**********************************************************************
    /* Life-cycle
    /**********************************************************************
     */

    protected JDKFromStringDeserializer(Class<?> valueType, int kind) {
        super(valueType);
        _kind = kind;
    }

    /**
     * Factory method for trying to find a deserializer for one of supported
     * types that have simple from-String serialization.
     */
    public static JDKFromStringDeserializer findDeserializer(Class<?> rawType)
    {
        int kind = 0;
        if (rawType == File.class) {
            kind = STD_FILE;
        } else if (rawType == URL.class) {
            kind = STD_URL;
        } else if (rawType == URI.class) {
            kind = STD_URI;
        } else if (rawType == Path.class) {
            kind = STD_PATH;
        } else if (rawType == Class.class) {
            kind = STD_CLASS;
        } else if (rawType == JavaType.class) {
            kind = STD_JAVA_TYPE;
        } else if (rawType == Currency.class) {
            kind = STD_CURRENCY;
        } else if (rawType == Pattern.class) {
            kind = STD_PATTERN;
        } else if (rawType == Locale.class) {
            kind = STD_LOCALE;
        } else if (rawType == Charset.class) {
            kind = STD_CHARSET;
        } else if (rawType == TimeZone.class) {
            kind = STD_TIME_ZONE;
        } else if (rawType == InetAddress.class) {
            kind = STD_INET_ADDRESS;
        } else if (rawType == InetSocketAddress.class) {
            kind = STD_INET_SOCKET_ADDRESS;
        } else if (rawType == StringBuilder.class) {
            return new StringBuilderDeserializer();
        } else {
            return null;
        }
        return new JDKFromStringDeserializer(rawType, kind);
    }

    /*
    /**********************************************************************
    /* A general-purpose implementation
    /**********************************************************************
     */

    // NOTE: public (unlike base class) to give JDKKeyDeserializer access
    @Override
    public Object _deserialize(String value, DeserializationContext ctxt)
        throws JacksonException,
            MalformedURLException, UnknownHostException
    {
        switch (_kind) {
        case STD_FILE:
            return new File(value);
        case STD_URL:
            return new URL(value);
        case STD_URI:
            return URI.create(value);
        case STD_PATH:
            // 06-Sep-2018, tatu: Offlined due to additions in [databind#2120]
            return NioPathHelper.deserialize(ctxt, value);
        case STD_CLASS:
            try {
                return ctxt.findClass(value);
            } catch (Exception e) {
                return ctxt.handleInstantiationProblem(_valueClass, value,
                        ClassUtil.getRootCause(e));
            }
        case STD_JAVA_TYPE:
            return ctxt.getTypeFactory().constructFromCanonical(value);
        case STD_CURRENCY:
            // will throw IAE if unknown:
            return Currency.getInstance(value);
        case STD_PATTERN:
            // will throw IAE (or its subclass) if malformed
            return Pattern.compile(value);
        case STD_LOCALE:
            return _deserializeLocale(value, ctxt);
        case STD_CHARSET:
            return Charset.forName(value);
        case STD_TIME_ZONE:
            return TimeZone.getTimeZone(value);
        case STD_INET_ADDRESS:
            return InetAddress.getByName(value);
        case STD_INET_SOCKET_ADDRESS:
            if (value.startsWith("[")) {
                // bracketed IPv6 (with port number)

                int i = value.lastIndexOf(']');
                if (i == -1) {
                    throw new InvalidFormatException(ctxt.getParser(),
                            "Bracketed IPv6 address must contain closing bracket",
                            value, InetSocketAddress.class);
                }

                int j = value.indexOf(':', i);
                int port = j > -1 ? Integer.parseInt(value.substring(j + 1)) : 0;
                return new InetSocketAddress(value.substring(0, i + 1), port);
            }
            int ix = value.indexOf(':');
            if (ix >= 0 && value.indexOf(':', ix + 1) < 0) {
                // host:port
                int port = Integer.parseInt(value.substring(ix+1));
                return new InetSocketAddress(value.substring(0, ix), port);
            }
            // host or unbracketed IPv6, without port number
            return new InetSocketAddress(value, 0);
        }
        VersionUtil.throwInternal();
        return null;
    }

    @Override // since 2.12
    public Object getEmptyValue(DeserializationContext ctxt)
    {
        switch (_kind) {
        case STD_URI:
            // As per [databind#398], URI requires special handling
            return URI.create("");
        case STD_LOCALE:
            // As per [databind#1123], Locale too
            return Locale.ROOT;
        }
        return super.getEmptyValue(ctxt);
    }

    @Override
    protected Object _deserializeFromEmptyStringDefault(DeserializationContext ctxt) throws JacksonException {
        // 09-Jun-2020, tatu: For backwards compatibility deserialize "as-empty"
        //    as URI and Locale did that in 2.11 (and StringBuilder probably ought to).
        //   But doing this here instead of super-class lets UUID return "as-null" instead
        return getEmptyValue(ctxt);
    }

    protected int _firstHyphenOrUnderscore(String str)
    {
        for (int i = 0, end = str.length(); i < end; ++i) {
            char c = str.charAt(i);
            if (c == '_' || c == '-') {
                return i;
            }
        }
        return -1;
    }

    private Locale _deserializeLocale(String value, DeserializationContext ctxt)
        throws JacksonException
    {
        // 10-Sep-2021, tatu: Looks like a simplified version might just work:
        return Locale.forLanguageTag(value);

        // ... but leaving Jackson 2.13.0 implementation below in case inspiration might
        // be needed; feel free to remove once 3.0.0 is released
/*
        int ix = _firstHyphenOrUnderscore(value);
        if (ix < 0) { // single argument
            return new Locale(value);
        }
        String first = value.substring(0, ix);
        value = value.substring(ix+1);
        ix = _firstHyphenOrUnderscore(value);
        if (ix < 0) { // two pieces
            return new Locale(first, value);
        }
        String second = value.substring(0, ix);
        // [databind#3259]: Support for BCP 47 java.util.Locale ser/deser
        int extMarkerIx = value.indexOf(LOCALE_EXT_MARKER);
        if (extMarkerIx < 0) {
            return new Locale(first, second, value.substring(ix+1));
        }
        return _deSerializeBCP47Locale(value, ix, first, second, extMarkerIx);
        */
    }

    /*
    private Locale _deSerializeBCP47Locale(String value, int ix, String first, String second,
            int extMarkerIx)
    {
        String third = "";
        try {
            // Below condition checks if variant value is present to handle empty variant values such as
            // en__#Latn_x-ext
            // _US_#Latn
            if (extMarkerIx > 0 && extMarkerIx > ix) {
                third = value.substring(ix + 1, extMarkerIx);
            }
            value = value.substring(extMarkerIx + 2);

            if (value.indexOf('_') < 0 && value.indexOf('-') < 0) {
                return new Locale.Builder().setLanguage(first)
                        .setRegion(second).setVariant(third).setScript(value).build();
            }
            if (value.indexOf('_') < 0) {
                ix = value.indexOf('-');
                return new Locale.Builder().setLanguage(first)
                        .setRegion(second).setVariant(third)
                        .setExtension(value.charAt(0), value.substring(ix + 1))
                        .build();
            }
            ix = value.indexOf('_');
            return new Locale.Builder().setLanguage(first)
                    .setRegion(second).setVariant(third)
                    .setScript(value.substring(0, ix))
                    .setExtension(value.charAt(ix + 1), value.substring(ix + 3))
                    .build();
        } catch(IllformedLocaleException ex) {
            return new Locale(first, second, third);
        }
    }
    */

    static class StringBuilderDeserializer extends JDKFromStringDeserializer
    {
        public StringBuilderDeserializer() {
            super(StringBuilder.class, -1);
        }

        @Override
        public LogicalType logicalType() {
            return LogicalType.Textual;
        }

        @Override
        public Object getEmptyValue(DeserializationContext ctxt) {
            return new StringBuilder();
        }

        @Override
        public Object deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException
        {
            String text = p.getValueAsString();
            if (text != null) {
                return _deserialize(text, ctxt);
            }
            return super.deserialize(p, ctxt);
        }

        @Override
        public Object _deserialize(String value, DeserializationContext ctxt)
        {
            return new StringBuilder(value);
        }
    }

    private static class NioPathHelper {
        private static final boolean areWindowsFilePathsSupported;
        static {
            boolean isWindowsRootFound = false;
            for (File file : File.listRoots()) {
                String path = file.getPath();
                if (path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':') {
                    isWindowsRootFound = true;
                    break;
                }
            }
            areWindowsFilePathsSupported = isWindowsRootFound;
        }

        public static Path deserialize(DeserializationContext ctxt, String value) throws JacksonException {
            // If someone gives us an input with no : at all, treat as local path, instead of failing
            // with invalid URI.
            if (value.indexOf(':') < 0) {
                return Paths.get(value);
            }

            if (areWindowsFilePathsSupported) {
                if (value.length() >= 2 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':') {
                    return Paths.get(value);
                }
            }

            final URI uri;
            try {
                uri = new URI(value);
            } catch (URISyntaxException e) {
                return (Path) ctxt.handleInstantiationProblem(Path.class, value, e);
            }
            try {
                return Paths.get(uri);
            } catch (FileSystemNotFoundException cause) {
                try {
                    final String scheme = uri.getScheme();
                    // We want to use the current thread's context class loader, not system class loader that is used in Paths.get():
                    for (FileSystemProvider provider : ServiceLoader.load(FileSystemProvider.class)) {
                        if (provider.getScheme().equalsIgnoreCase(scheme)) {
                            return provider.getPath(uri);
                        }
                    }
                    return (Path) ctxt.handleInstantiationProblem(Path.class, value, cause);
                } catch (Throwable e) {
                    e.addSuppressed(cause);
                    return (Path) ctxt.handleInstantiationProblem(Path.class, value, e);
                }
            } catch (Throwable e) {
                return (Path) ctxt.handleInstantiationProblem(Path.class, value, e);
            }
        }
    }
}
