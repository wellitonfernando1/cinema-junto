# Cinema Junto

Projeto Android para Welliton e um amigo assistirem à tela e ao áudio do celular pela internet. Android 10 ou superior. Nome no celular: Cinema Junto Chat Final.

## Estado desta entrega

O APK é gerado pelo GitHub Actions e o servidor está publicado no plano Free do Render em https://cinema-junto-welliton.onrender.com/. Esta versão redesenha o vídeo ao girar ou abrir o teclado, preserva a imagem ao entrar em tela cheia e oferece chat flutuante para quem transmite. Há testes de interface em emuladores Android 10 e 15. A compatibilidade com o YouCine precisa ser confirmada nos aparelhos reais.

## Instalar ou gerar novamente o APK sem Android Studio

O APK atualizado desta entrega é `Cinema-Junto-Chat-Final.apk`. No Google Drive o nome é `Cinema Junto Chat Final.apk`. Cada artefato do GitHub Actions expira após 14 dias; guarde uma cópia do APK. Se a versão anterior já estiver instalada, desinstale-a antes de instalar esta: as versões de teste compiladas em execuções diferentes usam assinaturas de depuração diferentes. A desinstalação apaga os dados locais do aplicativo.

1. Abra o [repositório Cinema Junto](https://github.com/wellitonfernando1/cinema-junto), que já contém o projeto e a pasta `.github`.
2. No GitHub, abra Actions > Gerar APK > Run workflow. A execução também acontece ao enviar código à branch `main`.
3. Aguarde a execução terminar. Ao final, baixe o arquivo Cinema-Junto-Chat-Final-APK em Artifacts e extraia o `Cinema-Junto-Chat-Final.apk`. Verifique também os resultados dos testes de interface.
4. Instale o APK nos dois celulares. O Android poderá pedir permissão para instalar aplicativos da origem escolhida. Esta é uma versão de teste com assinatura de depuração, para uso particular.
5. O computador não compila nada localmente. Toda a compilação acontece no GitHub Actions. Confira os limites e condições da sua conta antes de usar recursos de execução.

Se fizer alterações no projeto, envie os arquivos descompactados ao repositório. O fluxo precisa de `.github/workflows/apk.yml` para gerar um novo APK.

## Servidor publicado

Endereço: https://cinema-junto-welliton.onrender.com/. O aplicativo já traz esse endereço preenchido. O serviço usa o plano Free do Render, uma única instância, sem banco de dados nem recursos pagos. Código e configuração estão no [repositório público](https://github.com/wellitonfernando1/cinema-junto). Para acompanhar a publicação, abra o [painel do serviço](https://dashboard.render.com/web/srv-db24cg6k1f9s7392aph0).

O servidor pode levar algum tempo para responder após inatividade, conforme as regras da hospedagem. Conexões interrompidas exigem nova entrada. O código guarda salas apenas na memória: reiniciar o serviço encerra as salas. Configure uma única instância.

## Usar nos celulares

### Quem transmite

1. Confirme que o endereço HTTPS do servidor já está preenchido.
2. Toque em Transmitir minha tela e o som.
3. Autorize o áudio e ative **Aparecer sobre outros apps** quando solicitado. Volte ao Cinema Junto para autorizar a captura de tela. A permissão de áudio é necessária para copiar o som interno, não é utilizada para gravar seu microfone.
4. Em celulares que oferecem seleção de aplicativo, selecione o YouCine. Se selecionar a tela inteira, o amigo verá tudo que aparecer nela.
5. Aguarde a mensagem de confirmação da sala.
6. Toque em Compartilhar convite e envie-o ao amigo.
7. Abra o YouCine e dê play no filme. A notificação Cinema Junto mantém a captura ativa.
8. As mensagens aparecem por alguns segundos no alto. Toque no botão flutuante **Chat** para responder com o teclado ou emojis. Arraste o botão para mudá-lo de lugar. Toque em **×** ou feche o teclado para recolher o chat. Também há um botão Chat na notificação da transmissão.

### Quem assiste

1. Abra o Cinema Junto.
2. Cole o convite completo no campo indicado. O convite é para colar no aplicativo, não para reproduzir em um navegador.
3. Toque em Assistir ao meu amigo. A imagem aparece na área preta e o som é reproduzido pelo celular.
4. Toque em **Tela cheia** para ocupar a tela em paisagem; toque em **Voltar** para sair. Girar o aparelho mantém a conexão ativa. Os controles desaparecem após alguns segundos; toque na imagem para mostrá-los. **Ampliar** preenche a tela recortando as bordas da imagem; **Ajustar** volta a mostrar a imagem inteira.
5. Toque em **Chat** para escrever com o teclado ou usar os emojis rápidos. O filme continua na parte de cima e o teclado ocupa a parte de baixo. Ao fechar o teclado, o chat recolhe e a imagem volta a ocupar a tela. O editor de texto não abre em tela inteira na orientação paisagem. As mensagens dos dois participantes passam no topo do vídeo por alguns segundos.
6. Use fones para evitar eco se vocês também estiverem conversando por outro aplicativo.

### Encerrar

Quem transmite pode tocar em Encerrar na notificação ou dentro do aplicativo. Quem assiste toca em Encerrar. Uma sala admite um transmissor e um convidado e expira em duas horas. Para filmes mais longos, encerre e crie uma nova sala.

## Limites importantes da versão de teste

- Imagem JPEG até 960 pixels no maior lado e cerca de 12 quadros por segundo; áudio PCM mono a 44,1 kHz. É uma prova de funcionamento, ainda não tem a fluidez de um serviço de vídeo profissional.
- O áudio e a imagem não possuem sincronização por timestamps; pode ocorrer diferença perceptível entre fala e imagem. Melhorar esse comportamento exige uma próxima etapa de desenvolvimento com codecs de vídeo e áudio e controle de sincronismo.
- O envio integral por um servidor e o áudio sem compressão consomem dados. Prefira Wi-Fi; esta versão não serve como opção econômica para dados móveis.
- A gravação feita pelo gravador do fabricante não garante que outro aplicativo poderá capturar o YouCine. O áudio pode sair silencioso conforme a política do aplicativo. Não há recurso para contornar bloqueios.
- Tela compartilhada exige autorização a cada nova sessão. Não há captura automática, gravação permanente nem câmera/microfone de conversa.
- O chat flutuante só existe enquanto a transmissão está ativa e é removido ao encerrar. Ao compartilhar a tela inteira, a janela de chat e o teclado também podem aparecer na imagem compartilhada. Em Androids que oferecem compartilhar um aplicativo, essa seleção evita compartilhar outras janelas.
- Não há reconexão automática. Ao perder a rede, encerre e tente novamente.
- Abrir o aplicativo pela notificação pode recriar a tela de controles, mas a transmissão continua no serviço. Não inicie outra transmissão antes de encerrar a primeira.
- O destinatário precisa manter o aplicativo aberto. A rotação da tela é tratada sem recriar a conexão, mas o Android ainda pode encerrar o aplicativo por falta de memória ou restrições de bateria.
- Não testado em dispositivos físicos nesta entrega. Uma compilação aprovada também não garante captura compatível com o YouCine.

## Privacidade

Cada convite possui uma chave aleatória de 256 bits, que funciona como senha de acesso. Não publique esse convite. O servidor usa HTTPS/WSS quando publicado no Render e admite apenas um convidado, mas não há criptografia de ponta a ponta: o operador da hospedagem tem acesso ao trânsito dos dados. O código não salva filme, áudio, convite ou conteúdo da tela em disco. O compartilhamento do convite pelo seu mensageiro segue as condições desse mensageiro.

## Validação

Servidor: execute `npm ci` e `npm test` dentro de `server`. Os testes verificam isolamento entre salas, transmissão binária de imagem e áudio, encerramento quando o anfitrião sai, rejeição de convite inválido e limite de participantes.

Android: o fluxo GitHub Actions instala Java 17, Gradle 8.13 e Android SDK 35, compila o APK e os testes de instrumentação e executa os mesmos APKs em emuladores API 29 e 35. Os testes verificam pixels do filme após rotação, preservação da reprodução, vídeo visível acima do teclado, retorno à tela cheia ao fechar o teclado, mensagens compactas e leitura/envio do chat flutuante sobre a tela inicial do Android. As capturas de tela ficam nos artefatos de verificação de interface. Depois disso, teste nos dois aparelhos: autorizações, entrada pelo convite, imagem, áudio interno, atraso, giro de tela, encerramento pela notificação e reconexão manual após perda de rede.

Documentação de referência: https://developer.android.com/media/platform/av-capture , https://developer.android.com/media/grow/media-projection , https://render.com/docs/websocket , https://github.com/gradle/actions .
