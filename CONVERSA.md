# Chat Filme 0.3 — experimental

Aplicativo independente para conversar enquanto cada participante reproduz o filme no próprio serviço. O pacote br.com.cinemajunto.chatfilme é separado das versões antigas e instala como Chat Filme.

## Uso
1. Instale o APK nos dois aparelhos. O chat de texto funciona sem acesso ao microfone.
2. Uma pessoa cria um código de 4 a 24 letras/números. A outra usa Entrar com o mesmo código.
3. Toque em Configurar chat sobre o filme. O assistente abre as autorizações de sobreposição, rotação temporária e acessibilidade, pulando as já concedidas. O Android exige confirmações separadas.
4. O botão vermelho pede acesso ao microfone apenas na primeira utilização da voz; depois liga com um toque e desliga com outro. Chat abre o histórico e o campo de mensagem.
5. Para tentar tela dividida, escolha o aplicativo do filme. O Android e o aplicativo escolhido decidem se aceitam a divisão; também é possível usar Aplicativos recentes.
6. Sair ou Encerrar na notificação desliga a conversa e o microfone.

## Limites explícitos desta primeira versão
- Usa o relay existente em cinema-junto-welliton.onrender.com; o funcionamento externo será verificado separadamente.
- O servidor existente admite cinco aparelhos por sala e expira a sala em duas horas. Ainda não atende ao pedido anterior de remover esse limite.
- Uma pessoa fala por vez, até 30 segundos por turno. Após o limite, é necessário novo toque.
- Histórico local: últimas 200 mensagens recebidas por sala. Fechar e reabrir a mesma sala recupera o histórico local; mensagens enviadas enquanto o aparelho estava desconectado não são recuperadas.
- A conversa não solicita foco de áudio, não altera volume/modo de áudio e não envia comandos ao filme. Isso não garante que cada streaming continuará reproduzindo ao abrir o teclado ou dividir a tela.
- Sem captura/transmissão de tela, integração de login de streaming, sincronização automática ou acesso a notificações de outros apps.
- Microfone começa desligado e fica desligado após reconexão. Trocar de sala cancela voz e conexão anterior.
- APK experimental assinado com chave de teste do CI, conservada em cache; não é versão de produção. Não instrui o usuário a desativar Play Protect.

## Verificação
Workflow conversa.yml compila, verifica assinatura e executa testes no Android 15: primeiro texto sem permissão de microfone, depois voz, divisão de tela e temporizador com a autorização concedida.
Não substitui teste em aparelhos físicos, nem valida Netflix, Max, Darkflix ou YouCine.

## Atualização 0.2: chat em tela dividida
Requer Android 12L (API 32) ou mais recente. A divisão automática e o retorno foram desenvolvidos para o mecanismo de janelas do Android atual.
- Escolha o app do filme em Abrir aplicativo do filme. O nome selecionado permite localizar seu ícone em Recentes.
- Use Configurar chat sobre o filme para ativar sobreposição, rotação e Chat Filme · dividir tela na Acessibilidade, após ler as explicações no app. A rotação é temporária; as configurações anteriores são restauradas ao fechar ou encerrar o serviço.
- Ao tocar em Chat sobre o filme, solicita orientação vertical, usa a divisão do Android e abre uma tarefa separada com histórico, mensagem e teclado.
- Após três segundos sem digitar ou interagir no chat, esconde o teclado, preserva o rascunho e recolhe somente a tarefa do chat. Solicita a orientação anterior. Não envia automaticamente o rascunho.
- A acessibilidade acessa apenas os controles de Recentes do launcher e SystemUI. Não inspeciona a janela do streaming nem aciona seu player.
- Falha de divisão é informada; não é apresentada uma tela de chat cheia como se fosse divisão bem-sucedida.
- Limites: a divisão pode ser bloqueada pelo fabricante, launcher ou streaming. A rotação solicitada pode ser ignorada. Um streaming pode pausar por iniciativa própria quando muda de janela. Não há garantia de reprodução contínua em todos os serviços.

## Atualização 0.3
- Um único botão conduz a configuração; as confirmações continuam pertencendo ao Android.
- Entrar na sala e escrever não exige microfone nem uma solicitação de notificações.
- Os controles flutuantes ficam ocultos durante a divisão e reaparecem no retorno.
- Se a tarefa do chat reaparecer sem divisão, ela é recolhida para não deixar uma tela vazia com o filme em PiP. Isso não converte PiP em tela dividida.
- O chat abre apenas com a sala conectada e o aplicativo do filme escolhido. Falhas temporárias de conexão elegíveis tentam reconectar com a voz desligada.
- O teste externo de duas conexões confirmou entrada na sala e recebimento da mensagem por ambas em 17/11/2026. Não valida o comportamento dentro dos streamings.
