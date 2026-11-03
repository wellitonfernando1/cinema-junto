# Cinema Direto 0.22

Instale a versão 22 nos três aparelhos. As salas da 22 têm identificação própria e não se conectam à 21.

1. No aparelho que vai transmitir, crie a sala e autorize a captura.
2. Aguarde a confirmação de sala criada e envie a mesma palavra de quatro letras aos dois convidados.
3. Em cada aparelho que vai assistir, digite essa palavra e toque em Entrar na sala.

A sala permite um transmissor e dois espectadores. O vídeo, o áudio, o chat e a voz usam conexões diretas. O serviço público PeerServer apenas apresenta os aparelhos e troca os dados necessários para a conexão.

## Correções

- Oferta e resposta enviadas assim que ficam prontas, sem esperar a coleta completa de rotas de rede.
- Novas rotas ICE enviadas enquanto são descobertas, com fila para as que chegam antes da resposta.
- Dois endereços STUN para descoberta de rota e conexão independente para cada espectador.
- Prazos de entrada cancelados quando o canal abre e mensagem de falha em vez de espera indefinida.
- Confirmação periódica de presença para liberar a vaga de convidados que saíram sem avisar.
- Tela de entrada informa quando a sala foi localizada e está conectando ao transmissor.

## Limitação

Algumas combinações de operadoras, CGNAT ou bloqueios de rede impedem conexão direta. A versão não inclui retransmissão TURN. O teste em emuladores não garante funcionamento em todas as redes. Se a entrada falhar, teste outra rede Wi-Fi ou dados móveis. A qualidade máxima continua configurada em 1080p/60, dependendo dos aparelhos e da rede.
