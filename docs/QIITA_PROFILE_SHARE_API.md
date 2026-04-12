# Qiita プロファイル共有 API 連携仕様

`src/sync-qiita-profile-share.groovy` から送信する JSON 仕様です。

## 送信先

- パラメータ: `SHARE_API_URL`
- メソッド: `POST`
- ヘッダ:
  - `Content-Type: application/json`
  - `Authorization: Bearer <token>`（`SHARE_API_TOKEN_CREDENTIAL_ID` を設定した場合のみ）

## ペイロード例

```json
{
  "source": "jenkins-qiita-profile-share",
  "generatedAt": "2026-04-12T17:30:00+09:00",
  "accountCount": 2,
  "accounts": [
    {
      "credentialId": "jqit-qiita-access-token",
      "userId": "example-user-a",
      "followees": [
        {
          "id": "followee-1",
          "name": "Followee Name",
          "profile": "https://qiita-image-store.s3..."
        }
      ],
      "followingTags": [
        {
          "id": "jenkins",
          "iconUrl": "https://...",
          "items": 1234
        }
      ],
      "questions": [
        {
          "id": "q-xxxx",
          "title": "質問タイトル",
          "url": "https://qiita.com/...",
          "createdAt": "2026-04-01T12:00:00+09:00"
        }
      ]
    }
  ]
}
```

## ステータスコードの推奨

- `200` / `201`: 受信成功
- `400`: JSON 形式や必須項目不正
- `401` / `403`: API 認証エラー
- `500`: サーバー内部エラー

## 備考

- 収集時に無効な Qiita トークンは自動スキップされます。
- 有効アカウントが 0 件の場合はジョブ失敗になります。
- `DRY_RUN=true`（既定値）の場合は送信せず、収集のみ行います。
- `DRY_RUN=false` で共有送信する場合は `SHARE_API_URL` の設定が必須です。

## 定期実行

- 既定は「毎日1回（3時台）」に実行されます（`H 3 * * *`）。
- 3時間ごとにしたい場合は [src/sync-qiita-profile-share.groovy](src/sync-qiita-profile-share.groovy#L37) の cron を `H */3 * * *` に変更してください。
