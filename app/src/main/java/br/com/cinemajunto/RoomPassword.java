package br.com.cinemajunto;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;

/** A word is shown to people; only its derived room key is sent to the relay. */
final class RoomPassword {
    static String normalize(String input) {
        String word = Normalizer.normalize(input == null ? "" : input.trim(), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        if (!word.matches("[a-z0-9]{4,24}"))
            throw new IllegalArgumentException("Use uma palavra de 4 a 24 letras ou números, como pipoca42.");
        return word;
    }
    static String room(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                ("cinema-junto-sala-v1:" + normalize(input)).getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder();
            for (byte value : digest) key.append(String.format(Locale.ROOT, "%02x", value & 255));
            return key.toString();
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("Não foi possível criar a sala.", error); }
    }
    static synchronized String device(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("pairing", Context.MODE_PRIVATE);
        String value = preferences.getString("device", null);
        if (value == null || !value.matches("[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}")) {
            value = UUID.randomUUID().toString();
            preferences.edit().putString("device", value).apply();
        }
        return value;
    }
}
