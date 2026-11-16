package br.com.cinemajunto;

import android.app.*;
import android.os.*;
import android.content.*;
import android.media.session.*;
import android.provider.Settings;
import android.widget.*;
import android.net.Uri;
import java.util.*;
import org.json.*;

public class StreamingActivity extends Activity {
    private EditText name, invite, password, message;
    private TextView status, history;
    private LinearLayout roomPanel, entryPanel;
    private Spinner playerChoice;
    private CheckBox chatOnly;
    private final ArrayList<String> packages = new ArrayList<>();
    private final Handler handler = new Handler();
    private final Runnable refresh = new Runnable() { public void run() { render(); handler.postDelayed(this, 1000); } };
    private Button button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this); button.setText(label);
        button.setOnClickListener(v -> action.run()); parent.addView(button); return button;
    }
    private EditText input(LinearLayout parent, String hint) {
        EditText field = new EditText(this); field.setHint(hint); field.setSingleLine(true); parent.addView(field); return field;
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(1); root.setPadding(20, 20, 20, 20);
        scroll.addView(root); setContentView(scroll);
        TextView title = new TextView(this); title.setText("Cinema Junto"); title.setTextSize(26); root.addView(title);
        status = new TextView(this); root.addView(status);
        TextView instructions = new TextView(this);
        instructions.setText("Cada pessoa abre o mesmo filme no próprio aplicativo de streaming. Escolha o player abaixo. O anfitrião controla a reprodução. Compatibilidade em teste.");
        root.addView(instructions);
        button(root, "Ativar acesso aos controles", () -> new AlertDialog.Builder(this)
            .setMessage("O Android exige acesso às notificações para disponibilizar as sessões de mídia. O Cinema consulta os controles de reprodução. Você pode revogar o acesso nas configurações.")
            .setPositiveButton("Continuar", (d, w) -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
            .setNegativeButton("Cancelar", null).show());
        playerChoice = new Spinner(this); root.addView(playerChoice);
        button(root, "Atualizar lista de players", this::loadPlayers);
        button(root, "Usar player selecionado", () -> {
            RoomService service = RoomService.active;
            if (service != null && !packages.isEmpty()) {
                service.selectedPackage = packages.get(playerChoice.getSelectedItemPosition());
                getSharedPreferences("room", 0).edit().putString("player", service.selectedPackage).apply();
            }
        });
        entryPanel = new LinearLayout(this); entryPanel.setOrientation(1); root.addView(entryPanel);
        name = input(entryPanel, "Seu nome");
        invite = input(entryPanel, "Cole o convite para entrar");
        password = input(entryPanel, "Senha de quatro letras");
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        chatOnly = new CheckBox(this); chatOnly.setText("Entrar só no chat"); entryPanel.addView(chatOnly);
        button(entryPanel, "Criar sala", () -> startRoom(true));
        button(entryPanel, "Entrar na sala", () -> startRoom(false));
        roomPanel = new LinearLayout(this); roomPanel.setOrientation(1); root.addView(roomPanel);
        button(roomPanel, "Compartilhar convite", this::share);
        button(roomPanel, "Abrir streaming selecionado", () -> {
            RoomService s = RoomService.active;
            if (s == null) return;
            Intent launch = getPackageManager().getLaunchIntentForPackage(s.selectedPackage);
            if (launch != null) startActivity(launch); else toast("Abra o streaming pelo ícone do aplicativo.");
        });
        button(roomPanel, "Reproduzir", () -> { if (RoomService.active != null && RoomService.active.host) RoomService.active.control(true); });
        button(roomPanel, "Pausar", () -> { if (RoomService.active != null && RoomService.active.host) RoomService.active.control(false); });
        history = new TextView(this); history.setTextIsSelectable(true); roomPanel.addView(history);
        message = input(roomPanel, "Mensagem");
        button(roomPanel, "Enviar", () -> {
            RoomService s = RoomService.active;
            if (s != null && !message.getText().toString().trim().isEmpty() && s.chat(message.getText().toString())) message.setText("");
            else toast("Aguarde a conexão.");
        });
        button(roomPanel, "Sair da sala", () -> { if (RoomService.active != null) RoomService.active.leave(); });
        loadPlayers();
        if (RoomService.active == null) {
            String resume = getSharedPreferences("room", 0).getString("resume", null);
            if (resume != null) {
                Intent service = new Intent(this, RoomService.class).putExtra("request", resume)
                    .putExtra("chat", getSharedPreferences("room", 0).getBoolean("chat", false))
                    .putExtra("player", getSharedPreferences("room", 0).getString("player", ""));
                startForegroundService(service);
            }
        }
        render();
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private void loadPlayers() {
        packages.clear();
        ArrayList<String> labels = new ArrayList<>();
        try {
            List<MediaController> players = getSystemService(MediaSessionManager.class).getActiveSessions(new ComponentName(this, PlaybackAccess.class));
            for (MediaController player : players) {
                String pkg = player.getPackageName();
                if (packages.contains(pkg)) continue;
                packages.add(pkg);
                String label = pkg;
                try { label = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString(); } catch (Exception ignored) {}
                labels.add(label);
            }
        } catch (SecurityException error) { toast("Ative o acesso aos controles."); }
        if (labels.isEmpty()) labels.add("Abra um filme e atualize esta lista");
        playerChoice.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
    }
    private void startRoom(boolean create) {
        if (RoomService.active != null) { toast("Saia da sala atual antes de entrar em outra."); return; }
        String person = name.getText().toString().trim();
        if (person.isEmpty()) { toast("Digite seu nome."); return; }
        boolean onlyChat = !create && chatOnly.isChecked();
        if (!onlyChat && packages.isEmpty()) { toast("Abra o filme no streaming e atualize a lista de players."); return; }
        JSONObject request;
        if (create) {
            request = RoomService.json("type", "create", "name", person, "url", "https://example.invalid/native-session.mp4");
        } else {
            String value = invite.getText().toString().trim();
            String id = value;
            if (value.contains("https://")) {
                String link = value.substring(value.indexOf("https://")).split("\\s+")[0];
                id = Uri.parse(link).getQueryParameter("sala");
            }
            String pass = password.getText().toString().trim().toUpperCase(Locale.ROOT);
            int marker = value.indexOf("Senha:");
            if (pass.isEmpty() && marker >= 0) pass = value.substring(marker + 6).trim().split("\\s+")[0].toUpperCase(Locale.ROOT);
            if (id == null || id.isEmpty() || !pass.matches("[A-Z]{4}")) { toast("Cole o convite e informe a senha de quatro letras."); return; }
            request = RoomService.json("type", "join", "name", person, "room", id, "password", pass, "mode", onlyChat ? "chat" : "watch");
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 5);
        Intent service = new Intent(this, RoomService.class).putExtra("request", request.toString())
            .putExtra("chat", onlyChat).putExtra("player", onlyChat ? "" : packages.get(playerChoice.getSelectedItemPosition()));
        startForegroundService(service);
        handler.postDelayed(this::render, 200);
    }
    private void share() {
        RoomService s = RoomService.active;
        if (s == null || s.room.isEmpty()) return;
        String text = "Cinema Junto\nhttps://cinema-junto-welliton-teste.onrender.com/?sala=" + s.room + "\nSenha: " + s.password + "\nCole o link no aplicativo Cinema Junto. Abra o mesmo filme que o anfitrião.";
        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Enviar convite"));
    }
    private void render() {
        RoomService s = RoomService.active;
        entryPanel.setVisibility(s == null ? 0 : 8);
        roomPanel.setVisibility(s == null ? 8 : 0);
        status.setText(s == null ? "Abra seu streaming e selecione o player." : s.status + "\n" + s.count + " participantes");
        if (s != null) history.setText(android.text.TextUtils.join("\n\n", s.messages));
    }
    @Override public void onResume() { super.onResume(); RoomService.observer = this::render; handler.post(refresh); }
    @Override public void onPause() { RoomService.observer = null; handler.removeCallbacks(refresh); super.onPause(); }
}
