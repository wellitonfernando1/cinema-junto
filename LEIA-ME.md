# Cinema Junto

Projeto Android para Welliton e um amigo assistirem à tela e ao áudio do celular pela internet. Android 10 ou superior. Nome no celular: Cinema Junto Chat Final.

## Estado desta entrega

O APK é gerado pelo GitHub Actions e o servidor está publicado no plano Free do Render em https://cinema-junto-welliton.onrender.com/. A versão 0.7 traz um botão circular vermelho com ícone branco de microfone no canto superior direito para os dois participantes, usa uma senha simples escolhida por quem transmite e vincula cada sala ao primeiro aparelho convidado. Mantém vídeo AVC/H.264 nativo a até 24 quadros por segundo, pequeno buffer e relógio comum para áudio e imagem. O teclado fecha após cinco segundos sem uso e preserva o rascunho. Os dois participantes podem segurar o botão de microfone para falar. A compatibilidade com o YouCine e a fluidez precisam ser confirmadas nos aparelhos reais.

## Instalar ou gerar novamente o APK sem Android Studio

O APK atualizado desta entrega é `Cinema-Junto-Chat-Final.apk`. No Google Drive o nome é `Cinema Junto Chat Final v0.7.apk`. Cada artefato do GitHub Actions expira após 14 dias; guarde uma cópia do APK. Se a versão anterior já estiver instalada, desinstale-a antes de instalar esta: as versões de teste compiladas em execuções diferentes usam assinaturas de depuração diferentes. A desinstalação apaga os dados locais do aplicativo.

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
2. Toque em Transmitir minha tela e o som. Escolha uma palavra de 4 a 24 letras ou números, por exemplo `pipoca42`, e toque em Criar sala. Letras maiúsculas e acentos são normalizados para facilitar a digitação. Se a senha já estiver sendo usada em outra sala, o aplicativo pedirá outra.
3. Autorize o áudio e ative **Aparecer sobre outros apps** quando solicitado. Volte ao Cinema Junto para autorizar a captura de tela. A permissão de áudio permite copiar o som interno e, somente enquanto você segura o botão para falar, enviar a voz do microfone.
4. Em celulares que oferecem seleção de aplicativo, selecione o YouCine. Se selecionar a tela inteira, o amigo verá tudo que aparecer nela.
5. Aguarde a mensagem de confirmação da sala.
6. Toque em Compartilhar senha e envie apenas essa palavra ao amigo. Não há endereço ou código longo para copiar.
7. Abra o YouCine e dê play no filme. A notificação Cinema Junto mantém a captura ativa.
8. O botão circular vermelho com ícone de microfone fica no canto superior direito, sobre o filme. Mantenha o dedo nele para enviar sua voz; solte para encerrar. O som do filme fica silenciado nos dois aparelhos durante a fala, enquanto a imagem continua. Use fones para reduzir eco. Uma fala tem limite de 30 segundos e somente uma pessoa fala por vez.
9. No Android 13 e anteriores, o chat de quem transmite fica escondido, conforme a preferência desta entrega. Quem assiste mantém o próprio chat. Android 14 atualizado e posteriores oferecem chat flutuante; escolha compartilhar **Um app** para evitar enviar chat e teclado na captura. Ao escolher tela inteira, o botão de microfone e outras janelas também podem aparecer no vídeo.

### Quem assiste

1. Abra o Cinema Junto.
2. Digite a senha criada por quem transmite. Os dois aparelhos devem usar o endereço do servidor já preenchido. Convites completos de sessões antigas também continuam aceitos.
3. Toque em Assistir com a senha. A sala fica ligada a este aparelho convidado até quem transmite encerrar. O mesmo aparelho pode sair e entrar novamente; outro convidado é recusado mesmo após a saída do primeiro. Se apagar os dados do aplicativo ou reinstalá-lo durante a sala, quem transmite precisa encerrar e criar uma nova sala. Aguarde o pequeno carregamento inicial de aproximadamente 450 ms de áudio, além do tempo de conexão. O buffer reduz variações de chegada da rede. Se faltarem dados, áudio e vídeo aguardam juntos.
4. Toque em **Tela cheia** para ocupar a tela em paisagem; toque em **Voltar** para sair. Girar o aparelho mantém a conexão ativa. Os controles desaparecem após alguns segundos; toque na imagem para mostrá-los. **Ampliar** preenche a tela recortando as bordas da imagem; **Ajustar** volta a mostrar a imagem inteira.
5. Toque em **Chat** para escrever com o teclado ou usar os emojis rápidos. O filme continua na parte de cima e o teclado ocupa a parte de baixo. Ao fechar o teclado, ou após cinco segundos sem usar o chat, ele recolhe e a imagem volta a ocupar a tela. O rascunho é preservado. O editor de texto não abre em tela inteira na orientação paisagem. As mensagens aparecem no topo do vídeo por alguns segundos.
6. Use fones para evitar eco se vocês também estiverem conversando por outro aplicativo.
7. No canto superior direito há uma bolinha vermelha com ícone de microfone. Autorize o microfone na primeira vez e segure novamente. Durante a fala de qualquer participante, o filme continua com imagem e seu som fica silenciado. Ao soltar, o som retorna. Não há gravação permanente da voz.

### Encerrar

Quem transmite pode tocar em Encerrar na notificação ou dentro do aplicativo. Quem assiste toca em Encerrar. Uma sala admite um transmissor e um convidado e expira em duas horas. Para filmes mais longos, encerre e crie uma nova sala.

## Limites importantes da versão de teste

- Vídeo AVC/H.264 até 960 pixels no maior lado e até 24 quadros por segundo, aproximadamente 1,4 Mbps; áudio do filme PCM mono a 44,1 kHz. A qualidade e a fluidez variam conforme o aparelho e a rede. Voz usa PCM mono a 16 kHz.
- Áudio e imagem recebidos usam timestamps do mesmo relógio, e o vídeo acompanha o áudio realmente reproduzido. O buffer inicial é de 450 ms, com filas limitadas. Congestionamento pede um novo quadro completo para recuperar o vídeo.
- Quem transmite assiste ao filme local antes de ele chegar ao amigo. A captura não controla a pausa ou a posição do YouCine; o buffer acrescenta pequeno atraso e não garante o mesmo instante nos dois aparelhos. A sincronização exata exigiria ambos reproduzirem a mesma fonte em um player controlado pelo Cinema Junto.
- O envio integral por um servidor e o áudio sem compressão consomem dados. Prefira Wi-Fi; esta versão não serve como opção econômica para dados móveis.
- A gravação feita pelo gravador do fabricante não garante que outro aplicativo poderá capturar o YouCine. O áudio pode sair silencioso conforme a política do aplicativo. Não há recurso para contornar bloqueios.
- Tela compartilhada exige autorização a cada nova sessão. Não há captura automática nem gravação permanente. O microfone de conversa só funciona com o botão pressionado, tem limite de 30 segundos e para ao soltar, perder a conexão ou encerrar.
- Android 13 e anteriores ocultam o chat do transmissor nesta entrega. Compartilhamento exclusivo de um aplicativo exige Android 14 atualizado ou posterior. Compartilhar a tela inteira pode exibir informações de outros aplicativos. O botão flutuante de microfone é retirado ao encerrar.
- O som original do filme no transmissor é silenciado pelo volume de mídia do Android durante a fala; o volume anterior é restaurado ao terminar. O áudio da voz usa a saída de comunicação. Roteamento, fones e captura simultânea de áudio interno e microfone precisam de teste no aparelho real; alguns fabricantes podem impor restrições.
- Não há reconexão automática. Ao perder a rede, encerre e tente novamente.
- Abrir o aplicativo pela notificação pode recriar a tela de controles, mas a transmissão continua no serviço. Não inicie outra transmissão antes de encerrar a primeira.
- O destinatário precisa manter o aplicativo aberto. A rotação da tela é tratada sem recriar a conexão, mas o Android ainda pode encerrar o aplicativo por falta de memória ou restrições de bateria.
- Não testado em dispositivos físicos nesta entrega. Uma compilação aprovada também não garante captura compatível com o YouCine.

## Privacidade

A senha é escolhida por quem transmite. Compartilhe somente com seu amigo; uma palavra muito comum pode ser adivinhada. Internamente o aplicativo envia uma chave derivada dessa palavra por HTTPS/WSS, e o servidor limita tentativas de entrada. O primeiro convidado fica vinculado por um identificador aleatório persistido nesta instalação; isso limita a sala a um transmissor e um convidado, mas não é uma comprovação física do aparelho. Encerrar a transmissão libera uma nova associação ao recriar a sala.

Não há criptografia de ponta a ponta: o operador da hospedagem tem acesso ao trânsito dos dados. O servidor não salva filme, áudio, senha ou conteúdo da tela em disco. A senha criada fica nas preferências do aplicativo para permitir compartilhá-la enquanto a sala está ativa. O compartilhamento pelo seu mensageiro segue as condições desse mensageiro.

## Validação

Servidor: execute `npm ci` e `npm test` dentro de `server`. Os testes verificam isolamento entre salas, AVC/PCM com timestamps, recuperação após congestionamento, configuração para ingresso tardio, limites de participantes e microfone nos dois sentidos, com um falante por vez, limite de tempo e encerramento da voz ao sair. Também verificam senha ocupada/inexistente, vínculo do convidado, reconexão do mesmo aparelho, recusa de terceiro aparelho, compatibilidade com salas antigas e limitação de tentativas.

Android: o fluxo GitHub Actions instala Java 17, Gradle 8.13 e Android SDK 35, compila o APK e os testes de instrumentação e executa os mesmos APKs em emuladores API 29 e 35. Os testes incluem AVC codificado e decodificado de verdade, buffer, relógio do áudio, falta de dados, recuperação, imagem em tela cheia e muting durante a voz. Também verificam contraste do convite, teclado após cinco segundos, preservação do rascunho e captura do microfone nos dois papéis somente enquanto o botão está pressionado e a sala autorizou a fala. Os testes de interface isolam o chat flutuante mesmo nos Androids em que ele fica desativado no produto. Depois disso, teste nos dois aparelhos: permissões, convite, captura do YouCine, som interno, voz, fones/alto-falante, atraso, giro e encerramento. Emuladores não garantem o comportamento do fabricante ou do YouCine.

Documentação de referência: https://developer.android.com/media/platform/av-capture , https://developer.android.com/media/grow/media-projection , https://render.com/docs/websocket , https://github.com/gradle/actions .
