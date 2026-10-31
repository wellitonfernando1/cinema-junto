# Cinema Direto 0.19 — teste para dois aparelhos

Aplicativo separado da versão 0.18. Captura e reprodução partem da 0.18, com até 1920 pixels no lado maior, até 60 fps e reserva de reprodução de cerca de cinco segundos. O transporte passa a ser WebRTC DataChannel entre dois aparelhos. Não há conexão de sinalização, chat, voz ou mídia com o Render, nem fallback para um servidor pago. STUN auxilia a descoberta de endereço, sem transportar o filme.

## Como conectar

Instale o mesmo APK nos dois aparelhos. O YouCine continua no aplicativo instalado, sem integração de login.

1. Quem transmite toca em **1. Criar transmissão direta** e concede as permissões. Se o Android permitir compartilhar um aplicativo, pode escolher o YouCine. Volta ao Cinema Direto depois de autorizar.
2. Aguarda o aviso **Convite pronto**, toca em **2. Enviar convite** e copia ou compartilha o código completo com o amigo.
3. Quem assiste cola esse código no campo do Cinema Direto e toca em **Recebi um convite: preparar resposta**. Copia ou compartilha a resposta com quem transmite. Pode usar **Reabrir minha resposta** enquanto aguarda conexão.
4. Quem transmite toca em **3. Inserir resposta do amigo**, cola a resposta e toca em **Conectar**.
5. Após a confirmação de conexão direta, quem transmite abre o YouCine e reproduz o filme. **Encerrar** para e libera a captura.

Os códigos expiram em 15 minutos, contêm endereços de conexão e impressões digitais de autenticação do WebRTC. Envie-os apenas ao amigo, por um canal de confiança. Não contêm login ou senha do YouCine. Mudou a rede ou expirou o convite: encerre e crie outro. O uso de dados da internet dos aparelhos permanece.

## Limites do teste

- Apenas um transmissor e um receptor. Ambos precisam desta versão.
- Algumas combinações de operadoras/roteadores bloqueiam conexão direta. Esta versão informa a falha em vez de usar retransmissão. Não promete funcionamento em toda rede.
- Mantém captura de tela e áudio permitidos pelo Android e pelo aplicativo de origem. Não modifica nem contorna bloqueios de captura.
- DataChannel confiável e ordenado preserva os pacotes existentes e fragmenta quadros em mensagens menores. Perdas de rede podem aumentar atraso enquanto retransmite. A fluidez de um filme real e o desempenho em aparelhos diferentes ainda dependem de teste físico.
- A conversa por voz usa um participante por vez, com prioridade para quem já está falando.

## Validação

O workflow `Cinema Direto 0.19` compila o APK e executa testes nativos em Android 10 e Android 15. Eles conectam duas instâncias reais de WebRTC dentro do emulador, verificam remontagem de quadros de 400 KB, chat, voz, pedidos de quadro-chave, rejeição de convites inválidos/expirados e respostas de outra sessão. Isso não substitui teste entre duas redes físicas e não comprova fluidez de 1080p/60 fps.
