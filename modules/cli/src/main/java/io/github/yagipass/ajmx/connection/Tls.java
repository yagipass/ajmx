package io.github.yagipass.ajmx.connection;

import java.security.cert.Certificate;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record Tls(@Nullable List<Certificate> trustedCertificates) {}
