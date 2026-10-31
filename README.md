# jenkins-atom-jira-issue

JIRA 的**写入侧**：建单、评论、流转。

JIRA 插件家族多数是触发器（master 侧，不可移植），能搬的是这三步。流转有个坑：人写的是「In Progress」，API 要的是 id——原子自己查表换 id，匹配不到时把**可用的流转名**列出来，而不是只说一句失败。

鉴权两种：给用户名走 basic（用户名 + 密码/令牌），不给用户名走 `Bearer <token>`（个人访问令牌）。

```bash
./build.sh && ./tools/smoke.sh
```
