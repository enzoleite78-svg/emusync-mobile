# EmuSync Mobile

App Android não oficial, compatível com o [EmuSync](https://github.com/emu-sync/EmuSync), para sincronizar
saves de emuladores e ports entre o celular e o PC/Steam Deck pelo Google Drive.

Ele lê e grava os mesmos arquivos que o EmuSync usa na pasta `EmuSync_Data` do seu Drive.
Então um save feito no PC aparece no celular, e vice-versa.

## Baixar

Baixe o APK mais recente em **[Releases](https://github.com/enzoleite78-svg/emusync-mobile/releases/latest)**,
direto no celular, e abra o arquivo para instalar.

## Como funciona

- Os jogos são criados no **EmuSync do PC**. No celular, você escolhe a pasta de saves de cada jogo.
- **Antes de baixar um save da nuvem**, o app guarda um backup do save do celular (até 10 por jogo).
- **Se o save mudou nos dois lados**, o app pergunta qual manter, em vez de sobrescrever.
- **O primeiro envio de cada jogo precisa sair do PC.** Para um jogo que você só joga no celular,
  aponte o jogo no PC para uma pasta vazia e sincronize uma vez.

## Documentos

- [PLANO-ANDROID.md](PLANO-ANDROID.md): análise do formato do EmuSync e decisões técnicas.
- [CONFIGURAR-GOOGLE.md](CONFIGURAR-GOOGLE.md): configuração do Google Cloud (obrigatória).

## Compilar

```
gradlew.bat assembleDebug
```
O APK fica em `app/build/outputs/apk/debug/app-debug.apk`.

## Licença

GPL-3.0, a mesma licença do EmuSync. Este projeto não tem ligação com o autor do EmuSync.
