# SMBoss (Paper 1.21.11)

Build:  gradle build   ->  build/libs/SMBoss.jar  (can JDK 21)

Lenh (quyen smboss.admin, mac dinh OP):
- /smbreload                 tai lai boss-data.yml
- /smboss <id>               trieu hoi boss tai cho dung
- /cboss <id> <base64>       tao/cap nhat boss
- /delboss <id>              xoa boss
- /cdnbt <nbt>               loc NBT -> Base64 (bam de mo san /cboss hoac copy)

Cach them boss:
1. Dung gan mob, chay:  /data get entity @n[type=zombie]   (bam vao NBT de copy)
2. /cdnbt <dan NBT>  ->  bam [Mo san lenh /cboss], doi NhapID thanh ten, Enter
3. /smboss <id>

boss-data.yml:
bosses:
  zombie_king: "<base64>"

boss-data.yml (moi boss):  nbt, name, exp, spawn-message, drops
  drops: - item: DIAMOND / amount: "2-5" / chance: 90 (%) / name, lore, enchants (tuy chon)
