# Cinema Direto 0.23: revisão de estabilidade

Instale a versão 23 nos três aparelhos. Um transmite, dois assistem. Crie uma sala e envie a mesma palavra de quatro letras aos dois convidados.

## Falhas encontradas na 22

- Uma falha no WebSocket de descoberta encerrava todos os canais diretos, mesmo com vídeo funcionando.
- A vaga de um convidado ativo dependia de confirmação de presença a cada 25 segundos pelo serviço público. A ausência dessa confirmação podia expulsar alguém ainda conectado.
- Cada tentativa de entrada ganhava um identificador novo, permitindo que uma conexão antiga ocupasse a vaga da mesma instalação.
- Uma falha real no canal direto voltava à tela inicial, sem recuperação automática.
- Não havia proteção contra suspensão da CPU durante a conexão.

## Revisão

A sinalização reconecta com espera progressiva sem fechar os canais diretos ativos. Participantes conectados deixam de depender de presença no serviço de descoberta. O anfitrião confirma a atividade pelo próprio canal direto. Entrar novamente substitui somente a conexão anterior da mesma instalação. Falhas reais no canal do convidado iniciam reconexão automática, com até 90 segundos para recuperar, mantendo a sala e o outro espectador. A reprodução e a voz são limpas ao recuperar para não manter áudio silenciado ou quadros antigos. A CPU permanece ativa enquanto a conexão está aberta e é liberada ao encerrar.

## Verificação

Os 25 testes existentes do servidor passaram localmente. Os testes novos de Android descritos abaixo estão preparados, mas ainda não foram executados. A compilação local parou no acesso às dependências pelo proxy da plataforma, antes de compilar o aplicativo. Não há APK 23 validado para entrega.

A suíte inclui tráfego sintético contínuo próximo de 8 Mbit/s para dois espectadores durante sete minutos, interrupção de 35 segundos do serviço de descoberta depois do quinto minuto, três falhas provocadas no canal direto e três ciclos de saída e retorno da mesma instalação. Os testes de transporte usam emuladores Android 10 e 15 e não substituem a validação com os aparelhos e as redes reais.

A versão continua usando conexão direta para filme, voz e chat, sem TURN. Redes que impedem qualquer rota direta continuam exigindo outra rede. A qualidade máxima permanece configurada em 1080p/60 fps, dependente dos aparelhos e da rede. A estabilidade em uma sessão de longa duração real ainda precisa ser confirmada pelo usuário.
