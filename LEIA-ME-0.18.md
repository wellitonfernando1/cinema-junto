# Cinema Junto 0.18 — alterações preparadas

Base: 17A, commit 547b20614715eb52be965e25d7cde3724cfc615a.

Mantém captura de até 1920 pixels no lado maior, até 60 fps, alvo de 8 Mb/s e reserva de cinco segundos. Preserva voz, chat, teclado e proporção da imagem.

O receptor informa ao decodificador prioridade de reprodução e operação a 60 fps, reabastece a entrada assim que libera quadros e agenda a apresentação com até 120 ms de antecedência (antes 80 ms). O efeito na fluidez precisa ser medido em aparelhos reais; não há garantia de eliminar os microtravamentos.

Identificação única Cinema Junto 0.18, instalável junto à 17A. Não requer desinstalar a versão de comparação.

Validação local: 23/23 testes do servidor aprovados; git diff --check aprovado. APK e testes Android 10/15 pendentes. O ambiente local não possui Android SDK nem Gradle. O envio ao GitHub para executar a compilação foi bloqueado pela revisão automática por ausência de autorização explícita. Nenhuma alteração remota foi publicada.
