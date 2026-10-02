package io.github.yagipass.ajmx.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;

import com.google.errorprone.annotations.Var;

import io.github.yagipass.ajmx.codec.ValueDecoder;
import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.ErrorCode;

final class OperationResolver {
    static final Comparator<MBeanOperationInfo> BY_NAME_AND_SIGNATURE = Comparator
            .comparing(MBeanOperationInfo::getName).thenComparing(op -> String.join(",", types(op)));

    private OperationResolver() {
    }

    @SuppressWarnings("ArrayRecordComponent")
    record Resolved(Object[] params, String[] signature) {
    }

    static Resolved resolve(MBeanOperationInfo[] operations, String name, List<Object> args, List<String> signature) {
        List<MBeanOperationInfo> named = Arrays.stream(operations).filter(op -> op.getName().equals(name))
                .sorted(BY_NAME_AND_SIGNATURE).toList();
        if (named.isEmpty()) {
            throw new AjmxException(ErrorCode.OPERATION_NOT_FOUND, "Operation was not found").with("operation", name);
        }
        if (signature != null) {
            return resolveExplicit(named, name, args, signature);
        }

        List<MBeanOperationInfo> sameArity = named.stream().filter(op -> op.getSignature().length == args.size()).toList();
        if (sameArity.isEmpty()) {
            throw new AjmxException(ErrorCode.OPERATION_NOT_FOUND, "No overload takes this number of arguments")
                    .with("operation", name).with("argumentCount", args.size()).with("candidates", candidates(signatures(named)));
        }
        List<Resolved> matching = new ArrayList<>();
        @Var AjmxException mismatch = null;
        for (MBeanOperationInfo op : sameArity) {
            try {
                matching.add(new Resolved(convert(op, args), types(op).toArray(String[]::new)));
            } catch (AjmxException e) {
                mismatch = e;
            }
        }
        if (matching.size() > 1) {
            throw new AjmxException(ErrorCode.AMBIGUOUS_OPERATION, "Multiple matching operations")
                    .with("operation", name).with("candidates", candidates(matching.stream().map(r -> List.of(r.signature())).toList()));
        }
        if (matching.isEmpty()) {
            if (sameArity.size() == 1) {
                throw mismatch;
            }
            throw new AjmxException(ErrorCode.TYPE_CONVERSION_FAILED, "Arguments do not match any overload")
                    .with("operation", name).with("candidates", candidates(signatures(sameArity)));
        }
        return matching.getFirst();
    }

    private static Resolved resolveExplicit(List<MBeanOperationInfo> named, String name, List<Object> args, List<String> signature) {
        for (MBeanOperationInfo op : named) {
            if (types(op).equals(signature)) {
                if (args.size() != signature.size()) {
                    throw new AjmxException(ErrorCode.INVALID_ARGUMENT, "Argument count does not match the signature")
                            .with("operation", name).with("signature", signature).with("argumentCount", args.size());
                }
                return new Resolved(convert(op, args), signature.toArray(String[]::new));
            }
        }
        throw new AjmxException(ErrorCode.OPERATION_NOT_FOUND, "No overload has this signature")
                .with("operation", name).with("signature", signature).with("candidates", candidates(signatures(named)));
    }

    private static Object[] convert(MBeanOperationInfo op, List<Object> args) {
        MBeanParameterInfo[] params = op.getSignature();
        Object[] out = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            try {
                out[i] = ValueDecoder.decode(args.get(i), params[i].getType());
            } catch (AjmxException e) {
                Map<String, Object> context = new LinkedHashMap<>();
                context.put("operation", op.getName());
                context.put("argumentIndex", i);
                throw e.withContext(context);
            }
        }
        return out;
    }

    private static List<String> types(MBeanOperationInfo op) {
        return Arrays.stream(op.getSignature()).map(MBeanParameterInfo::getType).toList();
    }

    private static List<List<String>> signatures(List<MBeanOperationInfo> ops) {
        return ops.stream().map(OperationResolver::types).toList();
    }

    private static List<Map<String, Object>> candidates(List<List<String>> signatures) {
        return signatures.stream().map(s -> Map.<String, Object>of("signature", s)).toList();
    }
}
