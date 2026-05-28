# PJSUA2 Java Binding 調査メモ

## 目的

Phase 0では、JavaからPJSUA2を利用し、SIP Registration、UDP/IPv4のSIP待ち受け、PCMU音声mediaへのアクセスが実現できるかを確認する。

この段階では、PJSIP/PJSUA2のnative成果物はRepositoryにcommitしない。sourceおよびbuild成果物は`.deps/`配下に作成し、scriptで再現する。

## 参照した公式情報

PJSIP公式documentでは、PJSUA2 Java bindingのbuildには以下が必要とされている。

- PJPROJECTを`-fPIC`付きでbuildする。
- SWIGをinstallする。
- JDKをinstallする。
- `pjsip-apps/src/swig/java`で`make`および`make install`を実行する。

参照:

- [Building PJSUA2](https://docs.pjsip.org/en/2.15.1/pjsua2/building.html)
- [Build Instructions with GNU Build Systems](https://docs.pjsip.org/en/2.17/get-started/posix/build_instructions.html)

## macOS build手順

前提:

- macOS Apple Silicon。
- Command Line Tools導入済み。
- Java 21導入済み。
- Homebrew導入済み。

依存tool確認:

```sh
scripts/bootstrap-macos-deps.sh
```

PJSIP/PJSUA2 Java binding build:

```sh
scripts/build-pjsip-macos.sh
```

PJSIP本体とSWIG Java bindingをクリーン再生成する場合:

```sh
PJSIP_CLEAN=1 scripts/build-pjsip-macos.sh
```

versionを変更する場合:

```sh
PJSIP_VERSION=2.17 scripts/build-pjsip-macos.sh
```

## 初期build方針

- PJSIP tagは`2.17`を初期対象とする。
- SIP/RTPはUDP/IPv4を前提とする。
- codecはPCMU前提とする。
- Gateway用途ではmacOSの実音声デバイスを使わないため、PJSIPはnull audio構成でbuildする。
- video、SDL、FFmpeg、OpenH264、VPXは不要なため無効化する。
- native成果物は`.deps/`配下に置き、Repositoryには含めない。

## Phase 0の確認項目

- Javaから`org.pjsip.pjsua2.Endpoint`を生成できる。
- UDP/IPv4のtransportを作成できる。
- Registration設定を使ってSIP Registrationを実行できる。
- INVITEを受けてcall callbackを受信できる。
- PCMUでmedia negotiationできる。
- media frameをJava側へ取り出す方法、またはcustom PJMEDIA portが必要かを判断できる。

## 現時点の確認結果

- Java 21は利用可能。
- Command Line Toolsは利用可能。
- Homebrewは利用可能。
- SWIG 4.4.1をHomebrewで導入済み。
- PJSIP 2.17のsourceを`.deps/pjproject`へ取得済み。
- PJSIP 2.17を`-fPIC`、null audio、video無効構成でbuild済み。
- PJSUA2 Java bindingをbuild済み。
- `libpjsua2.jnilib`は`.deps/pjproject/pjsip-apps/src/swig/java/output`に生成済み。
- JavaからPJSUA2生成classを読み込めることを確認済み。
- GatewayアプリケーションからPJSUA2 Endpointを生成し、`libCreate()`、`libInit()`、UDP/IPv4 transport作成、`libStart()`を実行できることを確認済み。
- `AccountConfig`に設定ファイル由来のRegistration情報を設定し、SIP Registration開始まで実行できることを確認済み。
- `sip.backend`により、通常のplaceholder backendとPJSUA2 backendを切り替えられる。

確認command:

```sh
scripts/check-pjsua2-java.sh
scripts/run-pjsua2-startup-check.sh
```

外部INVITEを待ち受ける通常起動:

```sh
scripts/run-pjsua2-local.sh config/gateway.local.yaml
```

`scripts/run-pjsua2-startup-check.sh`は起動確認用であり、`--startup-check`によりRegistration開始後に停止する。継続待受を行う場合は`run-pjsua2-local.sh`を利用する。

確認時の出力では、OpenH264 native libraryが見つからない旨の警告が出る場合がある。これはvideo codec用の警告であり、本プロジェクトのMVPではvideoを利用しないため、現時点では問題として扱わない。

`scripts/run-pjsua2-startup-check.sh`では、`config/gateway.pjsua2.example.yaml`を使う。このexampleは`registrar.example.com`を指定しているため、実Registration成功ではなく、Account作成とRegistration送信開始までの確認を目的とする。実Registrarでの成功確認には、実際のDomain、User Name、Password、SIP Address、Registry Server address、Registry Server portを設定した別configを用意する。

macOSのサンドボックス環境では、UDP SIP transportのbindが`Operation not permitted`で失敗する場合がある。その場合は、権限許可された実行環境で同じscriptを実行する。

## 次の確認事項

- 実SIP環境またはlocal SIP serverを用意し、Registration結果を確認する。
- inbound INVITE callbackを受け、Java側のcall sessionへ接続する。
- PCMU media pathへのin-memory frame access方法を確認する。
