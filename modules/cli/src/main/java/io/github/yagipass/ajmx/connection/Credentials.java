package io.github.yagipass.ajmx.connection;

public record Credentials(String username, String password) {
    @Override
    public String toString() {
        return "Credentials[username=" + username + "]";
    }
}
