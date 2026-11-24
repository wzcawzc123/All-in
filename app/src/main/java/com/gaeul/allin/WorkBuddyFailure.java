package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.util.Locale;

/** Fixed diagnostic vocabulary. Exception messages and request data do not enter this snapshot. */
final class WorkBuddyFailure {
    enum Phase {
        REQUEST_PREPARATION, ACCOUNT_REFRESH, OPEN_CONNECTION, OUTBOUND_ADMISSION,
        OPEN_REQUEST_STREAM, WRITE_REQUEST_BODY, AWAIT_RESPONSE_HEADERS,
        OPEN_RESPONSE_BODY, READ_RESPONSE_BODY, DOWNSTREAM_DELIVERY
    }

    enum Cause {
        DNS, TLS, TIMEOUT, REFUSED, NETWORK, SOCKET, EOF, IO, PROTOCOL,
        HTTP_REJECTION, NO_HTTP_STATUS, LOCAL_REJECTION, CANCELLED, DELIVERY_UNCONFIRMED, UNKNOWN
    }

    final Phase phase;
    final Cause cause;

    private WorkBuddyFailure(Phase phase, Cause cause) {
        this.phase = phase;
        this.cause = cause;
    }

    static void record(UsageLedger.Row row, Phase phase, Cause cause) {
        if (row != null && ("workbuddy".equals(row.prov) || "workbuddy-ai".equals(row.prov)) && row.workBuddyFailure == null)
            row.workBuddyFailure = new WorkBuddyFailure(phase, cause);
    }

    static Cause classify(Throwable failure) {
        switch (FailureLayers.transport(failure)) {
            case DNS: return Cause.DNS;
            case TLS: return Cause.TLS;
            case TIMEOUT: return Cause.TIMEOUT;
            case REFUSED: return Cause.REFUSED;
            case NETWORK: return Cause.NETWORK;
            default: break;
        }
        boolean io = false;
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            if (failure instanceof EOFException) return Cause.EOF;
            if (failure instanceof ProtocolException) return Cause.PROTOCOL;
            if (failure instanceof SocketException) return Cause.SOCKET;
            io |= failure instanceof IOException;
        }
        return io ? Cause.IO : Cause.UNKNOWN;
    }

    String phaseName() { return phase.name().toLowerCase(Locale.ROOT); }
    String causeName() { return cause.name().toLowerCase(Locale.ROOT); }

    JSONObject json() {
        return new JSONObject().put("phase", phaseName()).put("cause", causeName());
    }

    static WorkBuddyFailure parse(JSONObject value) {
        if (value == null) return null;
        try {
            return new WorkBuddyFailure(Phase.valueOf(value.optString("phase").toUpperCase(Locale.ROOT)),
                    Cause.valueOf(value.optString("cause").toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
