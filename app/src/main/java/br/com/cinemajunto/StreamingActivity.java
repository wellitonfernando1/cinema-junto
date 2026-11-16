package br.com.cinemajunto;

import android.app.Activity;
import android.os.Bundle;
import android.content.ComponentName;
import android.content.Intent;
import android.media.session.MediaSessionManager;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.provider.Settings;
import android.widget.*;
import android.os.Handler;
import java.util.List;

public class StreamingActivity extends Activity {
    private LinearLayout list;
    private TextView status;
    private final Handler handler = new Handler();
    private final Runnable refresh = new Runnable() {
        public void run() { update(); handler.postDelayed(this, 2000); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        TextView title = new TextView(this);
        title.setText("Cinema Junto — teste de streamings");
        title.setTextSize(23);
        root.addView(title);
        TextView help = new TextView(this);
        help.setText("Ative o acesso aos controles, abra seu streaming e reproduza um filme. Volte aqui para verificar os comandos disponíveis. Esta versão testa a compatibilidade dos aplicativos.");
        root.addView(help);
        Button permission = new Button(this);
        permission.setText("Ativar acesso aos controles");
        permission.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
            .setMessage("O Android usa a permissão de acesso às notificações para disponibilizar as sessões de mídia. Este aplicativo consulta os controles de reprodução. Você pode revogar o acesso nas configurações.")
            .setPositiveButton("Abrir configurações", (dialog, which) -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
            .setNegativeButton("Cancelar", null).show());
        root.addView(permission);
        status = new TextView(this);
        root.addView(status);
        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll);
        setContentView(root);
    }
    @Override public void onResume() { super.onResume(); handler.post(refresh); }
    @Override public void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    private void update() {
        list.removeAllViews();
        try {
            MediaSessionManager manager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            List<MediaController> sessions = manager.getActiveSessions(new ComponentName(this, PlaybackAccess.class));
            status.setText(sessions.isEmpty() ? "Nenhum player detectado. Inicie um filme no aplicativo que deseja testar." : "Players detectados");
            for (MediaController controller : sessions) {
                PlaybackState state = controller.getPlaybackState();
                TextView label = new TextView(this);
                CharSequence name = controller.getPackageName();
                try { name = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(controller.getPackageName(), 0)); } catch (Exception ignored) {}
                label.setText(name + "\n" + controller.getPackageName() + (state == null ? "\nSem estado de reprodução" : "\nPosição informada: " + state.getPosition()/1000 + " s"));
                label.setTextSize(18);
                list.addView(label);
                long actions = state == null ? 0 : state.getActions();
                control("Reproduzir", (actions & PlaybackState.ACTION_PLAY) != 0, () -> controller.getTransportControls().play());
                control("Pausar", (actions & PlaybackState.ACTION_PAUSE) != 0, () -> controller.getTransportControls().pause());
                control("Avançar 10 segundos", (actions & PlaybackState.ACTION_SEEK_TO) != 0, () -> {
                    PlaybackState current = controller.getPlaybackState();
                    if (current != null) controller.getTransportControls().seekTo(current.getPosition() + 10000);
                });
            }
        } catch (SecurityException error) {
            status.setText("Ative o acesso aos controles nas configurações do Android.");
        }
    }
    private void control(String text, boolean supported, Runnable action) {
        Button button = new Button(this);
        button.setText(text + (supported ? "" : " — indisponível"));
        button.setEnabled(supported);
        button.setOnClickListener(v -> {
            try { action.run(); } catch (RuntimeException error) { Toast.makeText(this, "O player recusou o comando.", Toast.LENGTH_LONG).show(); }
        });
        list.addView(button);
    }
}
