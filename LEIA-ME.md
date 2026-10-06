# Cinema Junto

Projeto Android para Welliton e um amigo assistirem à tela e ao áudio do celular pela internet. Android 10 ou superior. Nome no celular: Cinema Junto.

## Estado desta entrega

Código da primeira versão de teste, serviço de conexão e configuração para compilação na nuvem. Não é um APK pronto. O teste do servidor é automatizado; a compilação Android e o teste em dois celulares precisam ser realizados antes de confirmar funcionamento. Não existe servidor publicado nem conta de hospedagem configurada nesta entrega.

## Gerar o APK sem Android Studio

1. Crie um repositório no GitHub e envie o conteúdo desta pasta, incluindo a pasta `.github`.
2. No GitHub, abra Actions > Gerar APK > Run workflow. A execução também acontece ao enviar código à branch `main`.
3. Aguarde a execução terminar. Ao final, baixe o arquivo Cinema-Junto-APK em Artifacts e extraia o `app-debug.apk`.
4. Instale o APK nos dois celulares. O Android poderá pedir permissão para instalar aplicativos da origem escolhida. Esta é uma versão de teste com assinatura de depuração, para uso particular.
5. O computador não compila nada localmente. Toda a compilação acontece no GitHub Actions. Confira os limites e condições da sua conta antes de usar recursos de execução.

Se você enviar os arquivos pelo navegador do GitHub, confira se `.github/workflows/apk.yml` foi enviado. Pastas ocultas podem não aparecer na seleção de arquivos do Windows. Não envie apenas este ZIP ao repositório: o fluxo precisa dos arquivos descompactados.

## Publicar o servidor

1. Conecte o GitHub ao Render.
2. No Render, use New > Blueprint e selecione o repositório. A configuração está em `render.yaml`.
3. Confira o plano e as condições antes de criar o serviço. A configuração pede o plano Free; disponibilidade, limites e exigências dependem do Render. Não autorize cobrança se não quiser um plano pago.
4. Aguarde a publicação e copie o endereço HTTPS do serviço, por exemplo `https://nome-do-servico.onrender.com`.
5. Esse endereço será informado no aplicativo de quem transmite.

Alternativa manual: New > Web Service; Root Directory `server`; Build Command `npm ci`; Start Command `npm start`; Health Check `/health`.

O servidor pode levar algum tempo para responder após inatividade, conforme as regras da hospedagem. Conexões interrompidas exigem nova entrada. O código guarda salas apenas na memória: reiniciar o serviço encerra as salas. Configure uma única instância.

## Usar nos celulares

### Quem transmite

1. Informe o endereço HTTPS do servidor.
2. Toque em Transmitir minha tela e o som.
3. Autorize a captura de áudio e de tela. A permissão de áudio é necessária para copiar o som interno, não é utilizada para gravar seu microfone.
4. Em celulares que oferecem seleção de aplicativo, selecione o YouCine. Se selecionar a tela inteira, o amigo verá tudo que aparecer nela.
5. Aguarde a mensagem de confirmação da sala.
6. Toque em Compartilhar convite e envie-o ao amigo.
7. Abra o YouCine e dê play no filme. A notificação Cinema Junto mantém a captura ativa.

### Quem assiste

1. Abra o Cinema Junto.
2. Cole o convite completo no campo indicado. O convite é para colar no aplicativo, não para reproduzir em um navegador.
3. Toque em Assistir ao meu amigo. A imagem aparece na área preta e o som é reproduzido pelo celular.
4. Use fones para evitar eco se vocês também estiverem conversando por outro aplicativo.

### Encerrar

Quem transmite pode tocar em Encerrar na notificação ou dentro do aplicativo. Quem assiste toca em Encerrar. Uma sala admite um transmissor e um convidado e expira em duas horas. Para filmes mais longos, encerre e crie uma nova sala.

## Limites importantes da versão de teste

- Imagem JPEG até 640 pixels no maior lado e cerca de 12 quadros por segundo; áudio PCM mono a 44,1 kHz. É uma prova de funcionamento, ainda não tem a fluidez de um serviço de vídeo profissional.
- O áudio e a imagem não possuem sincronização por timestamps; pode ocorrer diferença perceptível entre fala e imagem. Melhorar esse comportamento exige uma próxima etapa de desenvolvimento com codecs de vídeo e áudio e controle de sincronismo.
- O envio integral por um servidor e o áudio sem compressão consomem dados. Prefira Wi-Fi; esta versão não serve como opção econômica para dados móveis.
- A gravação feita pelo gravador do fabricante não garante que outro aplicativo poderá capturar o YouCine. O áudio pode sair silencioso conforme a política do aplicativo. Não há recurso para contornar bloqueios.
- Tela compartilhada exige autorização a cada nova sessão. Não há captura automática, gravação permanente nem câmera/microfone de conversa.
- Não há reconexão automática. Ao perder a rede, encerre e tente novamente.
- Abrir o aplicativo pela notificação pode recriar a tela de controles, mas a transmissão continua no serviço. Não inicie outra transmissão antes de encerrar a primeira.
- O destinatário precisa manter o aplicativo aberto. Girar o celular pode encerrar a recepção, exigindo nova entrada com o mesmo convite.
- Não testado em dispositivos físicos nesta entrega. Uma compilação aprovada também não garante captura compatível com o YouCine.

## Privacidade

Cada convite possui uma chave aleatória de 256 bits, que funciona como senha de acesso. Não publique esse convite. O servidor usa HTTPS/WSS quando publicado no Render e admite apenas um convidado, mas não há criptografia de ponta a ponta: o operador da hospedagem tem acesso ao trânsito dos dados. O código não salva filme, áudio, convite ou conteúdo da tela em disco. O compartilhamento do convite pelo seu mensageiro segue as condições desse mensageiro.

## Validação

Servidor: execute `npm ci` e `npm test` dentro de `server`. Os testes verificam isolamento entre salas, transmissão binária de imagem e áudio, encerramento quando o anfitrião sai, rejeição de convite inválido e limite de participantes.

Android: o fluxo GitHub Actions instala Java 17, Gradle 8.13 e Android SDK 35 e executa `gradle :app:assembleDebug`. Depois disso, teste nos dois aparelhos: autorizações, entrada pelo convite, imagem, áudio interno, atraso, giro de tela, encerramento pela notificação e reconexão manual após perda de rede.

Documentação de referência: https://developer.android.com/media/platform/av-capture , https://developer.android.com/media/grow/media-projection , https://render.com/docs/websocket , https://github.com/gradle/actions .
