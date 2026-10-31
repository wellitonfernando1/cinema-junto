package br.com.cinemajunto;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import java.io.ByteArrayInputStream;
import org.json.JSONObject;

/** Offline signaling only. Codes contain SDP fingerprints and network candidates, not credentials. */
final class DirectInvite {
    static final String PREFIX = "CJ19:";
    static final long VALID_MS = 15 * 60 * 1000;
    static final int MAX_SDP = 64 * 1024;
    static String encode(String session, String type, String sdp, long expires) throws Exception {
        byte[] plain = new JSONObject().put("session", session).put("type", type)
            .put("sdp", sdp).put("expires", expires).toString().getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_SDP) throw new IllegalArgumentException("Convite muito grande.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream zip = new DeflaterOutputStream(out)) { zip.write(plain); }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    }
    static JSONObject decode(String value, String expectedType) throws Exception {
        String code = value.trim();
        if (!code.startsWith(PREFIX) || code.length() > MAX_SDP)
            throw new IllegalArgumentException("Cole o código CJ19 completo, sem o texto da mensagem.");
        byte[] bytes = Base64.getUrlDecoder().decode(code.substring(PREFIX.length()));
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        try (InflaterInputStream zip = new InflaterInputStream(new ByteArrayInputStream(bytes))) {
            byte[] buf = new byte[2048]; int count;
            while ((count = zip.read(buf)) != -1) {
                if (plain.size() + count > MAX_SDP) throw new IllegalArgumentException("Convite inválido.");
                plain.write(buf, 0, count);
            }
        }
        JSONObject data = new JSONObject(plain.toString("UTF-8"));
        long remaining = data.getLong("expires") - System.currentTimeMillis();
        if (!expectedType.equals(data.getString("type")) ||
                !data.getString("session").matches("[a-f0-9-]{36}") ||
                !data.getString("sdp").startsWith("v=0") || remaining <= 0 || remaining > VALID_MS + 60000)
            throw new IllegalArgumentException("Código inválido, expirado ou de outra etapa. Crie um novo convite.");
        return data;
    }
}
