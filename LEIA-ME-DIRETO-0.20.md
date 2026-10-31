# Cinema Direto 0.20

Instale esta versão nos três aparelhos. Quem transmite toca em Criar sala e escolhe uma palavra com exatamente quatro letras sem acentos, como GATO. Após confirmar as permissões e receber a confirmação da sala, toca em Convidar pessoa e envia a mesma palavra aos dois convidados. Cada convidado digita a palavra e toca em Entrar na sala.

A sala aceita três pessoas no total. Vídeo, voz e chat passam pelos canais WebRTC entre os aparelhos. O servidor apenas localiza a sala e troca dados para estabelecer as conexões. Algumas redes não permitem ligação direta; não há retransmissão paga automática.

A entrada pela palavra depende da implantação do endpoint /signal preparado no PR #2. O APK compila e 25 testes de servidor passaram. Os novos testes nativos de três pares estão preparados para Android 10 e 15; a compilação desses testes passou, mas eles ainda não foram executados nesta entrega.
