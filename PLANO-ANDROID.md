# EmuSync para Android: análise e plano técnico

Análise feita lendo o código-fonte do EmuSync (branch `main`, versão v1.0.10) em 16/11/2026.
Objetivo: um app Android **compatível** com o EmuSync de PC/Steam Deck, usando **Google Drive**.

---

## 1. Como o EmuSync guarda os dados no Google Drive

Tudo fica numa única pasta na raiz do Drive chamada **`EmuSync_Data`** (busca por nome, sem pasta-pai).
Dentro dela:

| Arquivo | Conteúdo |
|---|---|
| `sync-sources.json` | Lista de dispositivos |
| `game-list.json` | Lista de jogos + metadados de sincronização |
| `game-{id}.zip` | Os saves de cada jogo, compactados |

O arquivo `game-{id}-metadata.json` existe como constante no código, mas não é usado.

### `sync-sources.json`
```json
{ "sources": [
  { "id": "3f2a...c9", "name": "MEU-PC", "storageProvider": 1, "osPlatform": 1 }
]}
```
- `id`: GUID sem hífens (`Guid.NewGuid().ToString("N")`), 32 caracteres hexadecimais.
- `storageProvider`: 1=GoogleDrive, 2=Dropbox, 3=OneDrive, 4=SharedFolder.
- `osPlatform`: 0=Unknown, 1=Windows, 2=Linux, 3=Mac. Não existe valor para Android.

### `game-list.json`
Os nomes dos campos são abreviados:
```json
{ "games": [
  {
    "id": "a1b2...",              // GUID sem hífens
    "b": "God of War II",          // nome do jogo
    "as": true,                    // AutoSync
    "sl": {                        // pasta do save em cada dispositivo
      "<id-do-PC>":   "C:\\Users\\X\\Documents\\PCSX2\\memcards",
      "<id-do-Deck>": "/home/deck/Emulation/saves/pcsx2/memcards"
    },
    "lsf": "<id-do-PC>",           // último dispositivo que enviou
    "lst": "2026-11-10T21:03:11.1234567Z", // hora do último sync (UTC)
    "lwt": "2026-11-10T20:59:40.5550000Z", // data de modificação mais recente dos saves enviados
    "sb": 524288,                  // tamanho em bytes
    "mlgb": null                   // limite de backups locais (opcional)
  }
]}
```

### `game-{id}.zip`
- Contém o **conteúdo** da pasta de save, com caminhos relativos.
- ⚠️ Quando o zip é criado no **Windows**, os caminhos internos usam `\` (ex.: `SAVEDATA\ULUS10041\DATA.BIN`). O Android precisa converter isso para `/` na hora de extrair.

---

## 2. Como o EmuSync decide se envia ou baixa

Lógica em `GameSyncManager.DetermineSyncType`:

1. Se o jogo nunca foi sincronizado (`lst` é nulo) e a pasta local existe → **upload**.
2. Se não há pasta definida para este dispositivo → "Unset directory".
3. Se há registro na nuvem e a pasta local não existe → **download**.
4. Compara a **data de modificação mais recente** da pasta local com `lwt`:
   - a local é mais nova → **upload**
   - a da nuvem é mais nova → **download**
   - são iguais → em sincronia.

Depois de um **download**, o EmuSync apaga a pasta local, extrai o zip e **força a data de modificação de todos os arquivos para `lwt`**. É isso que deixa as datas iguais e impede um reenvio.

Depois de um **upload**, o EmuSync grava `lwt` = a data mais recente encontrada nos arquivos locais, além de `lst` = agora e `lsf` = o id do dispositivo.

Antes de cada download, ele faz um backup local em zip (padrão de 10 por jogo).

**Não existe trava entre dispositivos.** A "trava" citada na wiki é só um `SemaphoreSlim` dentro do próprio processo. Duas máquinas editando `game-list.json` ao mesmo tempo podem sobrescrever uma à outra. Também **não existe detecção de conflito**: vence quem tiver a data mais nova.

---

## 3. ⚠️ Bloqueio principal: a permissão do Google Drive

O EmuSync pede a permissão **`drive.file`**. Com ela, um app só enxerga os arquivos que **ele mesmo** criou, e o "app" é identificado pelo projeto do Google Cloud do autor. Um app Android com credenciais próprias **não enxerga** a pasta `EmuSync_Data` criada pelo PC.

### Opções

| Opção | Como funciona | Prós | Contras |
|---|---|---|---|
| **A. Falar com o autor** (recomendado tentar primeiro) | Abrir uma issue propondo o app Android. O autor cria um cliente OAuth Android **no projeto dele**, e os dois apps passam a enxergar os mesmos arquivos. | Compatibilidade total, sem gambiarra. O app pode virar oficial. | Depende da resposta do autor. |
| **B. Projeto próprio com permissão `drive` completa** | Você cria um projeto no Google Cloud, pede o escopo `drive` e publica o app em modo "produção não verificada". Isso funciona para uso pessoal (até 100 usuários, com tela de aviso do Google). | Não depende de ninguém. | Ver as restrições abaixo. |

**Restrições da opção B**, porque o PC continua usando `drive.file`:
- O Android pode **ler e atualizar** arquivos que já existem. Mas qualquer arquivo **novo** que o Android criar fica **invisível para o PC**.
- Por isso, **o primeiro upload de cada jogo precisa ser feito no PC ou no Deck**. Só depois disso o celular entra na sincronização. O app vai bloquear o upload quando o zip ainda não existir na nuvem e explicar o motivo.
- O Android **não** cria jogos novos. Ele só associa uma pasta do celular a jogos que já existem.
- Não use o modo "teste" do Google Cloud: nesse modo o login expira a cada 7 dias.

> Usar o client ID do autor (que está público no `appsettings.json`) dentro de outro app vai contra os termos do Google. Não vamos fazer isso sem a autorização dele.

---

## 4. Diferenças do Android que exigem um design diferente

1. **Não dá para alterar a data de modificação dos arquivos.** O acesso a pastas no Android (Storage Access Framework) não permite isso. Se copiássemos a lógica do PC, todo download seria seguido de um upload desnecessário, num loop infinito.
   **Solução:** o app guarda localmente, para cada jogo, uma "impressão digital" da pasta (lista de arquivos com tamanho e data) e o valor de `lwt` da nuvem no último sync:
   - pasta local mudou e a nuvem não → **upload**
   - nuvem mudou e a pasta local não → **download**
   - **as duas mudaram → conflito: o app pergunta qual manter.** Isso é uma melhoria em relação ao original.
2. **No upload feito pelo Android, gravar `lwt` = a hora atual (UTC)**, e não a data dos arquivos. Isso garante que o PC veja a versão da nuvem como mais nova e baixe. Se gravássemos uma data mais antiga, o PC poderia achar que a versão local dele é mais nova e sobrescrever o save do celular.
3. **Nunca apagar a pasta raiz escolhida pelo usuário.** Apagar a pasta faz o Android perder a permissão de acesso. Antes de extrair, o app limpa só o conteúdo dela.
4. **A pasta `Android/data` é bloqueada** do Android 11 em diante. Para emuladores que salvam ali, as opções são: (a) mudar a pasta de dados no emulador, se ele permitir; (b) uma fase 2 com **Shizuku**, que ainda precisa ser testada na versão atual do Android.
5. Proteções extras: normalizar `\` para `/`, rejeitar entradas `..` no zip (zip-slip) e **preservar campos desconhecidos** do JSON ao regravar, para não apagar campos que o autor adicionar em versões futuras.
6. Na nuvem, o celular se registra em `sync-sources.json` com `osPlatform: 2` (Linux), para aparecer com o logo do Linux no app do PC em vez de "Unknown". O nome fica, por exemplo, "Android – Galaxy S25". Na lista de jogos, o caminho exibido é o caminho legível (ex.: `/storage/emulated/0/PSP/SAVEDATA`). O endereço interno do SAF fica salvo só no celular.

---

## 5. Seus emuladores

Os caminhos abaixo **ainda precisam ser confirmados no seu celular**.

| Sistema | Android | PC | Observação |
|---|---|---|---|
| **PSP** | PPSSPP: pasta escolhida pelo usuário, `.../PSP/SAVEDATA` | PPSSPP: `memstick/PSP/SAVEDATA` | ✅ Fácil. Mesmo formato e mesma estrutura. |
| **RetroArch** | Pasta `saves/` (e `states/`), configurável | Pasta `saves/` | ✅ Funciona se a pasta estiver fora de `Android/data` e a organização (por core/conteúdo) for **igual** no PC e no celular. |
| **PS2** | NetherSX2/AetherSX2/ARMSX2: pasta `memcards` | PCSX2: `memcards` | ⚠️ Os memory cards `.ps2` são compatíveis, mas os **nomes dos arquivos** precisam ser iguais. Se o emulador salvar em `Android/data`, cai no item 4.4. |
| **PS Vita** | Vita3K: `.../ux0/user/00/savedata` | Vita3K: mesma estrutura | ⚠️ Por padrão fica em `Android/data`. Precisa verificar se dá para mudar a pasta. |
| **Ports** | Qualquer pasta | Qualquer pasta | ✅ Funciona por pasta, igual ao EmuSync. Basta adicionar cada port quando instalar. |

Lembre que o EmuSync sincroniza **a pasta inteira**: a pasta mapeada no PC e a do celular precisam ter o mesmo conteúdo e a mesma estrutura.

---

## 6. Tecnologia proposta

- **Kotlin + Jetpack Compose**, com Android 8.0 ou superior como mínimo e a versão mais recente como alvo.
- **Google Identity Services** (`AuthorizationClient`) para login e permissão do Drive, e a **API REST do Drive v3**.
- **Storage Access Framework**, com permissão persistente por pasta.
- **WorkManager** para o sync automático. O intervalo mínimo do Android é de 15 minutos, e existe também um botão "Sincronizar agora".
- **Room** ou DataStore para os dados locais: id do dispositivo, pastas, impressões digitais e o manifesto de backups.
- Licença **GPL-3.0**, com crédito ao projeto EmuSync original.

---

## 7. Fases

1. **MVP:** login no Google → encontrar `EmuSync_Data` → registrar o celular → listar os jogos → escolher a pasta de cada jogo → sync manual com backup antes do download.
2. Sync automático (WorkManager) e notificações.
3. Tela de conflito e restauração de backups.
4. Acesso a `Android/data` via Shizuku, se ainda for viável.
5. Detecção automática de emuladores conhecidos e adição em lote.

## 8. O que você vai precisar

- Instalar o **Android Studio** e o **Git**.
- Uma conta Google para criar o projeto no **Google Cloud Console** (opção B), ou a resposta do autor (opção A).
- Uma pasta permanente no PC para o projeto.
- Um cabo USB, ou ativar a depuração sem fio, para instalar o app no celular.
