package com.specshield.ledgerline.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * The client IP used as the rate-limit key.
 *
 * <p>{@code X-Forwarded-For} is attacker controlled unless it was written by our own proxy. It is therefore read only
 * when the TCP peer is a configured trusted proxy, and then from the right: each trusted hop is skipped and the first
 * untrusted address is the client. Anything the client prepended stays to the left and is ignored. Entries that are
 * not IP literals end the walk (no DNS lookups on header content).
 */
public final class ClientIpResolver {

    private final List<IpAddressMatcher> trusted;

    public ClientIpResolver(List<String> trustedCidrs) {
        this.trusted = trustedCidrs.stream().map(IpAddressMatcher::new).toList();
    }

    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (!isTrusted(peer)) {
            return peer;
        }
        List<String> hops = new ArrayList<>();
        for (String header : Collections.list(request.getHeaders("X-Forwarded-For"))) {
            for (String hop : header.split(",")) {
                hops.add(hop.trim());
            }
        }
        String client = peer;
        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = hops.get(i);
            if (!isIpLiteral(hop)) {
                break;
            }
            client = hop;
            if (!isTrusted(hop)) {
                break;
            }
        }
        return client;
    }

    private boolean isTrusted(String address) {
        if (!isIpLiteral(address)) {
            return false;
        }
        for (IpAddressMatcher matcher : trusted) {
            if (matcher.matches(address)) {
                return true;
            }
        }
        return false;
    }

    /** IPv4 dotted quad or IPv6 hex/colon form; never a host name. */
    static boolean isIpLiteral(String s) {
        if (s == null || s.isEmpty() || s.length() > 45) {
            return false;
        }
        if (s.indexOf(':') >= 0) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                boolean ok = c == ':' || c == '.' || Character.digit(c, 16) >= 0;
                if (!ok) {
                    return false;
                }
            }
            return true;
        }
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) {
                return false;
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }
}
