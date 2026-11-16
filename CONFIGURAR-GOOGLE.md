# Configurar o Google Cloud (uma vez só)

> **Status (16/11/2026): já configurado** no projeto `emusync-mobile`: API do Drive ativada,
> escopo `drive` adicionado, cliente Android criado e app **publicado (em produção, não verificado)**.
> Página inicial e política de privacidade: <https://enzoleite78-svg.github.io/emusync-mobile/>
> (pasta `docs/`, publicada pelo GitHub Pages). O login não expira mais a cada 7 dias.

O app precisa de um projeto seu no Google Cloud para poder acessar o seu Google Drive.
Não precisa colar nenhuma chave no código: o Google reconhece o app pelo **nome do pacote**
e pela **impressão digital SHA-1** do certificado que assina o APK.

Dados do app:
- **Nome do pacote:** `app.emusyncmobile`
- **SHA-1 (debug):** `6C:CD:3C:84:08:DA:E7:98:EF:9A:30:43:47:70:DD:3B:B0:DD:9F:12`
  (é o certificado de debug deste PC: se você compilar em outro PC, o SHA-1 muda e precisa
  adicionar outro cliente Android com o novo valor)

## Passo a passo

1. Acesse <https://console.cloud.google.com/> com **a mesma conta Google do EmuSync no PC**.
2. No topo, clique no seletor de projetos → **Novo projeto** → nome `EmuSync Mobile` → **Criar**.
   Confira se o projeto novo está selecionado.
3. Menu ☰ → **APIs e serviços** → **Biblioteca** → procure **Google Drive API** → **Ativar**.
4. Menu ☰ → **Google Auth Platform** (ou "Tela de permissão OAuth") → **Começar**:
   - Nome do app: `EmuSync Mobile`. E-mail de suporte: o seu.
   - Público-alvo: **Externo**.
   - E-mail de contato: o seu → aceite a política → **Criar**.
5. Em **Acesso a dados** → **Adicionar ou remover escopos** → marque
   `https://www.googleapis.com/auth/drive` (Ver, editar, criar e excluir todos os seus arquivos do Google Drive)
   → **Atualizar** → **Salvar**.
6. Em **Público-alvo** → **Publicar app** → confirme.
   - Isso é importante: no modo "Teste" o login expira a cada 7 dias.
   - O app vai ficar "não verificado". Para uso pessoal tudo bem: no login aparece um aviso,
     é só clicar em **Avançado** → **Acessar EmuSync Mobile (não seguro)**.
7. Em **Clientes** → **Criar cliente**:
   - Tipo: **Android**
   - Nome do pacote: `app.emusyncmobile`
   - Impressão digital SHA-1: a que está no topo deste arquivo
   - **Criar**.

Pronto. Pode levar alguns minutos para o Google ativar o cliente.

## Por que a permissão completa do Drive?

O EmuSync do PC usa a permissão `drive.file`, que só deixa cada app ver os arquivos que ele mesmo criou.
Para o celular enxergar a pasta `EmuSync_Data` criada pelo PC, ele precisa da permissão completa.
O app só lê e altera arquivos dentro de `EmuSync_Data` e **nunca cria arquivos lá**, porque o PC não os enxergaria.
