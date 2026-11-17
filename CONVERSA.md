# Cinema Conversa 0.1 — experimental

Aplicativo independente para conversar enquanto cada participante reproduz o filme no próprio serviço. O pacote br.com.cinemajunto.conversa é separado das versões antigas.

## Uso
1. Instale o APK nos dois aparelhos. Permita o microfone ao entrar.
2. Uma pessoa cria um código de 4 a 24 letras/números. A outra usa Entrar com o mesmo código.
3. Toque em Bolinha e conceda a permissão de aparecer sobre outros apps.
4. O botão vermelho liga a voz com um toque e desliga com outro. Chat abre o histórico e o campo de mensagem.
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
Workflow conversa.yml compila, verifica assinatura, instala e executa três testes básicos em Android 10 e 15 antes de publicar a prévia.
Não substitui teste em aparelhos físicos, nem valida Netflix, Max, Darkflix ou YouCine.
