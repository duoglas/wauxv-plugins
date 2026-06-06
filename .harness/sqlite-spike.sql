.timer on
.echo on
PRAGMA journal_mode=WAL;
PRAGMA synchronous=NORMAL;
CREATE TABLE speak(grp TEXT, wxid TEXT, last_speak INTEGER, first_seen INTEGER, PRIMARY KEY(grp,wxid)) WITHOUT ROWID;

-- [A] 单行 autocommit upsert: 模拟"若每条消息同步写 DB"的代价(确认必须批量, 不能放热路径)
INSERT INTO speak VALUES('g1','w_single',1000,1000)
  ON CONFLICT(grp,wxid) DO UPDATE SET last_speak=excluded.last_speak;

-- [B] 500 行单事务批量 upsert: 模拟后台 flush 一次提交一个 500 人群的脏增量
BEGIN;
INSERT INTO speak
  SELECT 'g1','w'||x, x*10, x*10
  FROM (WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<500) SELECT x FROM c)
  ON CONFLICT(grp,wxid) DO UPDATE SET last_speak=excluded.last_speak;
COMMIT;

-- [C] 再来一次 500 行(全部命中 ON CONFLICT 走 UPDATE 路径): 模拟稳态重复发言
BEGIN;
INSERT INTO speak
  SELECT 'g1','w'||x, x*10+1, x*10
  FROM (WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<500) SELECT x FROM c)
  ON CONFLICT(grp,wxid) DO UPDATE SET last_speak=excluded.last_speak;
COMMIT;

-- [D] 踢潜水查询: 索引直查群内 last_speak < cutoff 的成员
SELECT count(*) FROM speak WHERE grp='g1' AND last_speak < 2500;

-- [E] 裁剪超期: DELETE WHERE 一句搞定 RB-2 的裁剪
DELETE FROM speak WHERE last_speak < 1000;

SELECT count(*) AS remaining FROM speak;
