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

versionを変更する場合:

```sh
PJSIP_VERSION=2.17 scripts/build-pjsip-macos.sh
```

## 初期build方針

- PJSIP tagは`2.17`を初期対象とする。
- SIP/RTPはUDP/IPv4を前提とする。
- codecはPCMU前提とする。
- video機能は不要なため無効化する。
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
- PJSIP 2.17を`-fPIC`付きでbuild済み。
- PJSUA2 Java bindingをbuild済み。
- `libpjsua2.jnilib`は`.deps/pjproject/pjsip-apps/src/swig/java/output`に生成済み。
- JavaからPJSUA2生成classを読み込めることを確認済み。

確認command:

```sh
scripts/check-pjsua2-java.sh
```

確認時の出力では、OpenH264 native libraryが見つからない旨の警告が出る場合がある。これはvideo codec用の警告であり、本プロジェクトのMVPではvideoを利用しないため、現時点では問題として扱わない。

## 次の確認事項

- 本アプリケーション側からPJSUA2 Java bindingをoptional dependencyとして参照する構成を作る。
- `Endpoint`の生成、`libCreate()`、UDP/IPv4 transport作成、`libStart()`までをGateway内のadapterとして実装する。
- 実credentialを使わずに、設定値から`AccountConfig`を組み立てられることを確認する。
- 実SIP環境またはlocal SIP serverを用意し、Registration結果を確認する。
