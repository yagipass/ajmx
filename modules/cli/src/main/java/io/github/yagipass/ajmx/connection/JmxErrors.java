package io.github.yagipass.ajmx.connection;

import java.io.IOException;
import java.io.InvalidClassException;
import java.io.NotSerializableException;
import java.io.ObjectStreamException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.InvalidAttributeValueException;
import javax.management.JMException;
import javax.management.MBeanException;
import javax.management.MalformedObjectNameException;
import javax.management.ReflectionException;
import javax.management.RuntimeErrorException;
import javax.management.RuntimeMBeanException;
import javax.management.RuntimeOperationsException;

import com.google.errorprone.annotations.Var;

import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.ErrorCode;

final class JmxErrors {
    private static final String UNAVAILABLE = "A value or exception has a type that is not available to ajmx";
    private static final String NOT_SERIALIZABLE = "A value or exception is not serializable, so the JVM cannot send it";

    private JmxErrors() {
    }

    static AjmxException translate(Throwable t, Map<String, ?> context) {
        AjmxException e = t instanceof AjmxException a ? a : classify(t);
        return e.withContext(context);
    }

    private static AjmxException classify(Throwable t) {
        if (t instanceof InstanceNotFoundException) {
            return new AjmxException(ErrorCode.MBEAN_NOT_FOUND, "MBean was not found", t);
        }
        if (t instanceof AttributeNotFoundException) {
            return new AjmxException(ErrorCode.ATTRIBUTE_NOT_FOUND, "Attribute was not found", t);
        }
        if (t instanceof InvalidAttributeValueException) {
            return new AjmxException(ErrorCode.TYPE_CONVERSION_FAILED, "The MBean rejected the attribute value", t);
        }
        if (t instanceof MalformedObjectNameException) {
            return new AjmxException(ErrorCode.INVALID_ARGUMENT, "Invalid ObjectName", t);
        }
        if (t instanceof ReflectionException re) {
            if (re.getTargetException() instanceof NoSuchMethodException) {
                return new AjmxException(ErrorCode.OPERATION_NOT_FOUND, "Operation was not found", t);
            }
            return thrownByMBean(re.getTargetException(), t);
        }
        if (t instanceof MBeanException me) {
            return thrownByMBean(me.getTargetException(), t);
        }
        if (t instanceof RuntimeMBeanException rme) {
            return thrownByMBean(rme.getTargetException(), t);
        }
        if (t instanceof RuntimeErrorException ree) {
            return thrownByMBean(ree.getTargetError(), t);
        }
        if (t instanceof RuntimeOperationsException roe) {
            return thrownByMBean(roe.getTargetException(), t);
        }
        AjmxException unsupported = findUnsupportedType(t);
        if (unsupported != null) {
            return unsupported;
        }
        if (causes(t).stream().anyMatch(SecurityException.class::isInstance)) {
            return new AjmxException(ErrorCode.AUTH_FAILED, "Access denied by the JMX server", t);
        }
        if (t instanceof IOException) {
            return new AjmxException(ErrorCode.CONNECTION_FAILED, "Connection to the JVM failed", t);
        }
        if (t instanceof JMException) {
            return thrownByMBean(t, t);
        }
        return AjmxException.wrap(t);
    }

    private static AjmxException thrownByMBean(Throwable target, Throwable t) {
        Throwable cause = target != null ? target : t;
        return new AjmxException(ErrorCode.REMOTE_EXCEPTION, "The MBean threw an exception", t)
                .with("exceptionClass", cause.getClass().getName())
                .with("exceptionMessage", cause.getMessage());
    }

    private static AjmxException findUnsupportedType(Throwable t) {
        @Var boolean serializationFailed = false;
        for (Throwable c : causes(t)) {
            if (c instanceof ClassNotFoundException) {
                return unsupported(UNAVAILABLE, className(c.getMessage()), t);
            }
            if (c instanceof InvalidClassException ice) {
                return unsupported(UNAVAILABLE, ice.classname, t);
            }
            if (c instanceof NotSerializableException) {
                return unsupported(NOT_SERIALIZABLE, c.getMessage(), t);
            }
            if (c.getClass().getName().startsWith("org.graalvm.nativeimage.Missing")) {
                return unsupported("A value or exception has a type that the native binary cannot handle", null, t);
            }
            serializationFailed |= c instanceof ObjectStreamException;
        }
        return serializationFailed ? unsupported("A value or exception could not be serialized", null, t) : null;
    }

    private static List<Throwable> causes(Throwable t) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable c = t; c != null && seen.add(c); c = c.getCause()) {
            chain.add(c);
        }
        return chain;
    }

    private static AjmxException unsupported(String message, String className, Throwable t) {
        return new AjmxException(ErrorCode.UNSUPPORTED_TYPE, message, t).with("class", className);
    }

    private static String className(String message) {
        if (message == null) {
            return null;
        }
        int space = message.indexOf(' ');
        return space < 0 ? message : message.substring(0, space);
    }
}
