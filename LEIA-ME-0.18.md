# Cinema Junto 0.18

Base: 17A (547b20614715eb52be965e25d7cde3724cfc615a).

Mantém até 1920 pixels no lado maior, até 60 fps, alvo de 8 Mb/s e reserva de cinco segundos. Preserva voz, chat, teclado e proporção da imagem.

O receptor solicita prioridade de reprodução e operação a 60 fps, reabastece a entrada do decodificador após liberar quadros e agenda a apresentação até 120 ms antes (anteriormente 80 ms). A melhoria precisa ser confirmada em aparelhos reais.

Instalação independente: Cinema Junto 0.18 pode coexistir com a 17A. Instale nos dois aparelhos.

Validação: 23 testes locais do servidor aprovados. Compilação e testes de Android 10/15: consultar o GitHub Actions da branch release/0.18. Não confundir aprovação automatizada com garantia de ausência de microtravamentos no aparelho real.
