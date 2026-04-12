# Qiita フォロー同期ジョブ仕様

`src/sync-qiita-profile-share.groovy` の実行仕様です。

## 目的

- 3アカウント（または複数アカウント）のフォロー中ユーザー一覧を統合し、各アカウントへ不足分を反映する
- 3アカウント（または複数アカウント）のフォロー中タグ一覧を統合し、各アカウントへ不足分を反映する

## 同期の動作

- 各アカウントで次を収集:
  - `/authenticated_user/followees`
  - `/authenticated_user/following_tags`
- 全アカウントの和集合（union）を作成
- 各アカウントについて、自分に不足しているユーザー/タグのみを追加フォロー
- 自分自身の userId はフォロー対象から除外

## 主要パラメータ

- `QIITA_TOKEN_CREDENTIAL_IDS`: 同期対象の Credential ID（カンマ区切り）
- `HTTP_TIMEOUT_SEC`: Qiita API タイムアウト秒
- `HTTP_RETRY_COUNT`: Qiita API リトライ回数
- `MAX_PAGE_COUNT`: ページング取得上限
- `DRY_RUN`: true の場合は反映せず、差分ログのみ

## エンドポイント備考

Qiita API の差異に備え、フォロー更新は候補パスを順に試行します。

- ユーザーフォロー: `/users/:id/following` → `/users/:id/follow`
- タグフォロー: `/tags/:id/following` → `/tags/:id/follow`

## 実行結果

- すべて成功: `SUCCESS`
- 一部失敗あり: `UNSTABLE`
- 有効アカウントが0件: `FAILURE`

## 定期実行

- 既定は「毎日1回（3時台）」に実行されます（`H 3 * * *`）。
- 3時間ごとにしたい場合は [src/sync-qiita-profile-share.groovy](src/sync-qiita-profile-share.groovy#L37) の cron を `H */3 * * *` に変更してください。
