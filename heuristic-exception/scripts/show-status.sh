#!/usr/bin/env sh

set -e

echo "DB1 approval_history:"
docker compose exec mysql-approval mysql -uapp -papp -e "SELECT COUNT(*) AS cnt FROM approval_db.approval_history;"
echo "DB2 ums_send_history:"
docker compose exec mysql-ums mysql -uapp -papp -e "SELECT COUNT(*) AS cnt FROM ums_db.ums_send_history;"
DB1=$(docker compose exec mysql-approval mysql -N -B -uapp -papp -e "SELECT COUNT(*) FROM approval_db.approval_history;" 2>/dev/null | tr -d '\r')
DB2=$(docker compose exec mysql-ums mysql -N -B -uapp -papp -e "SELECT COUNT(*) FROM ums_db.ums_send_history;" 2>/dev/null | tr -d '\r')
echo "차이(DB1-DB2): $((DB1-DB2))"
