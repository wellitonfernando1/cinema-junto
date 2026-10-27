# Cinema Junto 0.18

Base: 17A. Perfil até 1920 pixels no lado maior, até 60 fps, 8 Mb/s e reserva de cinco segundos. Preserva áudio, voz, chat, teclado e proporção.

Receptor: solicita prioridade de reprodução e taxa de operação de 60 fps; reabastece o decoder após liberar quadros; limita a entrega antecipada à tela a 30 ms para reduzir ocupação de buffers de saída; consulta a reprodução em intervalos de 1 ms durante reprodução ativa.

Instale Cinema Junto 0.18 nos dois aparelhos. A instalação é independente da 17A e pede suas próprias permissões. Encerre a transmissão anterior antes de abrir a nova versão.

Validação da revisão c50edfb89e32bf3cea4bcfc8c9423c0d56bfe262: compilação aprovada; 23/23 testes do servidor; 22/22 no Android 15; 21/22 no Android 10. A falha restante é perda de quadros na reprodução de 60 fps usando OMX.google.h264.decoder no emulador Android 10 (23 descartes durante a janela avaliada). Não houve recarregamento nem lacunas na origem nessa medição. Isso não comprova a causa dos microtravamentos dos aparelhos reais nem confirma sua eliminação. A versão está disponível para validação no aparelho; a fluidez final permanece pendente.

Execução: https://github.com/wellitonfernando1/cinema-junto/actions/runs/40595528225
