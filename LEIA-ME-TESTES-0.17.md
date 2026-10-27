# Cinema Junto 0.17: comparação A/B

Os dois aplicativos podem ficar instalados juntos com a versão anterior.

- Cinema A 0.17: captura de até 1920 pixels no lado maior, até 60 fps.
- Cinema B 0.17: mesma resolução e taxa-alvo de 8 Mb/s, até 30 fps.
- As duas versões mantêm proporção, áudio, chat com teclado nativo, cinco aparelhos por sala e reserva temporária de aproximadamente cinco segundos. O conteúdo da reserva é descartado ao encerrar.
- A usa mais processamento e pode favorecer o movimento. B usa menos quadros e oferece mais bits por quadro à mesma taxa, preservando nitidez. São hipóteses para comparação no aparelho real, não garantia de eliminar engasgos.
- Não há qualidade adaptativa por internet neste teste. O encoder pode limitar o bitrate ao suportado. A cai para 30/24 fps se a capacidade declarada não comportar 60; B pode cair para 24. Se o codec não suporta a resolução nessa orientação, escolhe a maior resolução compatível por aproximação em passos de 5%. Ao girar para paisagem tenta novamente o limite de 1920 pixels. O diagnóstico do receptor informa a resolução real.
- 1920×1080 é possível quando a região compartilhada é 16:9 e tem ao menos essas dimensões. Compartilhar a tela inteira de um celular mais alongado mantém a proporção e pode resultar, por exemplo, em 1920×864. Não inventa detalhes nem quadros que não existam na origem. O filme escolhido em 1080p continua sendo recapturado, não é enviado como arquivo original.

## Como comparar

1. Instale A e B nos DOIS aparelhos. Permita captura, áudio e botões flutuantes em cada app quando solicitado.
2. Abra apenas A no transmissor e A no receptor, crie uma sala e assista à mesma cena com movimento e fala por três minutos, em tela cheia. Aguarde a reserva inicial.
3. Ao perceber o problema, pressione e segure a imagem: abre Diagnóstico. Copie ou tire uma captura. O diagnóstico não envia dados automaticamente.
4. Encerre a sala e a transmissão A. Abra B nos dois aparelhos e repita a MESMA cena, com o mesmo 1080p no app de origem e a mesma rede. Não rode duas transmissões simultaneamente.
5. Anote: A ou B; modelo de cada aparelho; imagem engasga? áudio corta? acontece no próprio filme no transmissor? Envie o diagnóstico de cada versão.

Comece com apenas um receptor para comparar. Depois experimente os demais. Os 300 Mb/s do plano não medem upload, estabilidade do Wi-Fi, capacidade do servidor ou do codec do aparelho.

## Alterações compartilhadas

- Identidades de instalação distintas e rótulos A/B visíveis.
- Solicitação de frequência da tela a partir de timestamps estáveis da mídia (preserva 23,976/29,97/59,94), em Android 11+, sem forçar transição com tela preta. Em Android 10 mantém a apresentação normal. O Android decide se aceita a solicitação.
- Fila de envio e de retransmissão limitada a 4 MiB, para tolerar rajadas em alta qualidade antes de descartar quadros. A fila temporária de rede pode aumentar o atraso além dos cinco segundos durante congestionamento.
- Pedido de quadro-chave de outro participante deixa de suspender os quadros intermediários enviados aos participantes que já estão assistindo.
- Diagnóstico local de resolução, codec, quadros recebidos/apresentados/descartados, reserva e intervalos na origem. Os intervalos também incluem rotações e mudanças de superfície; não são uma medida isolada da internet.

## Validação

A compilação e os testes automatizados das duas versões são executados no GitHub Actions, em Android 10 e 15. O teste de reprodução usa AVC real, 30/60 fps conforme o perfil, perda temporária de entrega por dois segundos, áudio, tela cheia, teclado e retomada. O teste de captura usa MediaProjection real e verifica a resolução configurada. Isso não comprova fluidez no celular do usuário; a comparação A/B acima é necessária.
