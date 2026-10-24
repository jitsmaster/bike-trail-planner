## Trail connectivity to the OSM road network (shared node test)

Data: Overpass API, OSM data (c) OpenStreetMap contributors (ODbL). Full machine-readable detail: `docs/safety-probe-raw/islands.json`.

| trail | rel | member ways | trail nodes | non-member highway ways touching a trail node | shared nodes | of which with a drivable road class | VERDICT | chains | length (m) | Overpass instance |
|---|---|---|---|---|---|---|---|---|---|---|
| White River Trail | [3508527](https://www.openstreetmap.org/relation/3508527) | 5 | 220 | 24 | 28 | 1 | **CONNECTED (28 shared nodes)** | 1 | 3879 | overpass-api.de |
| Interurban Trail Milton | [6554034](https://www.openstreetmap.org/relation/6554034) | 9 | 118 | 13 | 14 | 6 | **CONNECTED (14 shared nodes)** | 2 | 4315 | overpass-api.de |
| Interurban Trail Edgewood | [8826064](https://www.openstreetmap.org/relation/8826064) | 3 | 35 | 6 | 4 | 4 | **CONNECTED (4 shared nodes)** | 1 | 1330 | overpass.kumi.systems |
| Riverwalk Trail | [9605517](https://www.openstreetmap.org/relation/9605517) | 10 | 179 | 35 | 35 | 11 | **CONNECTED (35 shared nodes)** | 2 | 6670 | overpass-api.de |
| Sumner Link Trail | [9605518](https://www.openstreetmap.org/relation/9605518) | 42 | 394 | 66 | 68 | 30 | **CONNECTED (68 shared nodes)** | 5 | 9619 | overpass-api.de |
| Lake Tapps Parkway Trail | [9605519](https://www.openstreetmap.org/relation/9605519) | 3 | 98 | 17 | 17 | 6 | **CONNECTED (17 shared nodes)** | 1 | 3016 | overpass.kumi.systems |

`shared nodes` = trail node ids that are also node ids of a non-member `highway=*` way (a real OSM junction). `drivable road class` = motorway/trunk/primary/secondary/tertiary/unclassified/residential/living_street/service. Highway class mix of the shared ways (per trail, in `islands.json`):

* **White River Trail**: cycleway x8, footway x13, path x1, service x1, track x1
* **Interurban Trail Milton**: cycleway x3, footway x1, path x3, residential x2, secondary x1, service x2, tertiary x1
* **Interurban Trail Edgewood**: residential x1, secondary x2, service x2, tertiary x1
* **Riverwalk Trail**: cycleway x11, footway x8, path x4, primary x1, residential x6, service x4, tertiary x1
* **Sumner Link Trail**: cycleway x8, footway x20, path x9, residential x4, secondary x5, service x16, tertiary x4
* **Lake Tapps Parkway Trail**: cycleway x2, footway x8, path x2, residential x2, secondary x2, service x1

### Example shared nodes (up to 10 per trail)

| trail | trail node | lat | lon | road way | road name | highway | surface |
|---|---|---|---|---|---|---|---|
| White River Trail | 12594216248 | 47.282987 | -122.192666 | [354380326](https://www.openstreetmap.org/way/354380326) | - | track | - |
| White River Trail | 4353441166 | 47.279007 | -122.198154 | [437531322](https://www.openstreetmap.org/way/437531322) | - | footway | asphalt |
| White River Trail | 2669377493 | 47.279248 | -122.197857 | [437531327](https://www.openstreetmap.org/way/437531327) | - | footway | - |
| White River Trail | 4569347956 | 47.279518 | -122.198030 | [461442379](https://www.openstreetmap.org/way/461442379) | - | footway | compacted |
| White River Trail | 2669377522 | 47.274564 | -122.207385 | [674021791](https://www.openstreetmap.org/way/674021791) | - | cycleway | asphalt |
| White River Trail | 6312114902 | 47.274439 | -122.208768 | [674021792](https://www.openstreetmap.org/way/674021792) | - | cycleway | - |
| White River Trail | 2669374349 | 47.264747 | -122.229213 | [674202154](https://www.openstreetmap.org/way/674202154) | - | service | - |
| White River Trail | 7888851531 | 47.272073 | -122.218721 | [845567702](https://www.openstreetmap.org/way/845567702) | - | cycleway | asphalt |
| White River Trail | 2669377477 | 47.273530 | -122.216238 | [845567703](https://www.openstreetmap.org/way/845567703) | - | cycleway | asphalt |
| White River Trail | 10696070530 | 47.273365 | -122.217014 | [845567705](https://www.openstreetmap.org/way/845567705) | - | cycleway | - |
| Interurban Trail Milton | 53175521 | 47.261063 | -122.300768 | [6420938](https://www.openstreetmap.org/way/6420938) | South 380th Street | residential | asphalt |
| Interurban Trail Milton | 110480391 | 47.249872 | -122.323494 | [12193441](https://www.openstreetmap.org/way/12193441) | Kent Street | residential | asphalt |
| Interurban Trail Milton | 13174384557 | 47.240728 | -122.336437 | [215897125](https://www.openstreetmap.org/way/215897125) | Wapato Way East | secondary | asphalt |
| Interurban Trail Milton | 9352149140 | 47.239329 | -122.334345 | [1013669076](https://www.openstreetmap.org/way/1013669076) | - | service | asphalt |
| Interurban Trail Milton | 9352149145 | 47.239283 | -122.336163 | [1013669077](https://www.openstreetmap.org/way/1013669077) | Milton Interurban Trail | cycleway | asphalt |
| Interurban Trail Milton | 11259007739 | 47.243490 | -122.334054 | [1215212095](https://www.openstreetmap.org/way/1215212095) | - | path | - |
| Interurban Trail Milton | 2379361679 | 47.243335 | -122.334533 | [1215477816](https://www.openstreetmap.org/way/1215477816) | Milton Interurban Trail | cycleway | paved |
| Interurban Trail Milton | 12196160808 | 47.247537 | -122.324913 | [1317769728](https://www.openstreetmap.org/way/1317769728) | - | service | asphalt |
| Interurban Trail Milton | 3213532066 | 47.240730 | -122.336152 | [1331402721](https://www.openstreetmap.org/way/1331402721) | - | cycleway | concrete |
| Interurban Trail Milton | 12860134678 | 47.254528 | -122.323490 | [1389229768](https://www.openstreetmap.org/way/1389229768) | - | path | - |
| Interurban Trail Edgewood | 645714471 | 47.258350 | -122.293487 | [50697841](https://www.openstreetmap.org/way/50697841) | Military Road East | secondary | asphalt |
| Interurban Trail Edgewood | 12882622451 | 47.258322 | -122.293147 | [259300196](https://www.openstreetmap.org/way/259300196) | - | service | compacted |
| Interurban Trail Edgewood | 2646868507 | 47.256065 | -122.277330 | [765606369](https://www.openstreetmap.org/way/765606369) | 114th Avenue East | tertiary | asphalt |
| Interurban Trail Edgewood | 645714471 | 47.258350 | -122.293487 | [1183624635](https://www.openstreetmap.org/way/1183624635) | 31st Avenue | residential | asphalt |
| Interurban Trail Edgewood | 12882604574 | 47.256857 | -122.290987 | [1391736645](https://www.openstreetmap.org/way/1391736645) | - | service | - |
| Interurban Trail Edgewood | 645714471 | 47.258350 | -122.293487 | [1431656263](https://www.openstreetmap.org/way/1431656263) | Military Road South | secondary | - |
| Riverwalk Trail | 6486202582 | 47.195472 | -122.287791 | [12193756](https://www.openstreetmap.org/way/12193756) | 5th Avenue Northeast | residential | - |
| Riverwalk Trail | 6486202541 | 47.193958 | -122.287778 | [12201578](https://www.openstreetmap.org/way/12201578) | 4th Avenue Northeast | residential | - |
| Riverwalk Trail | 6486202546 | 47.197703 | -122.287798 | [12223099](https://www.openstreetmap.org/way/12223099) | 8th Avenue Northeast | residential | asphalt |
| Riverwalk Trail | 6486202544 | 47.196931 | -122.287806 | [12226132](https://www.openstreetmap.org/way/12226132) | 7th Avenue Northeast | residential | - |
| Riverwalk Trail | 6486202537 | 47.192702 | -122.285780 | [12235846](https://www.openstreetmap.org/way/12235846) | 7th Street Northeast | residential | concrete |
| Riverwalk Trail | 6486202549 | 47.192715 | -122.287638 | [12236770](https://www.openstreetmap.org/way/12236770) | 5th Street Northeast | tertiary | asphalt |
| Riverwalk Trail | 110880940 | 47.207103 | -122.323052 | [12240663](https://www.openstreetmap.org/way/12240663) | 80th Avenue East | residential | - |
| Riverwalk Trail | 2101094375 | 47.202573 | -122.298470 | [200123038](https://www.openstreetmap.org/way/200123038) | - | cycleway | asphalt |
| Riverwalk Trail | 6486202535 | 47.192699 | -122.284605 | [282578108](https://www.openstreetmap.org/way/282578108) | - | service | - |
| Riverwalk Trail | 3621050859 | 47.196515 | -122.251303 | [356690848](https://www.openstreetmap.org/way/356690848) | - | path | asphalt |
| Sumner Link Trail | 5479081486 | 47.210397 | -122.242134 | [12182723](https://www.openstreetmap.org/way/12182723) | Fryar Avenue | tertiary | asphalt |
| Sumner Link Trail | 110422926 | 47.206017 | -122.242880 | [12183068](https://www.openstreetmap.org/way/12183068) | 57th Street East | residential | asphalt |
| Sumner Link Trail | 110422931 | 47.207430 | -122.242147 | [12231716](https://www.openstreetmap.org/way/12231716) | Zehnder Street | tertiary | asphalt |
| Sumner Link Trail | 110790321 | 47.200730 | -122.252799 | [12231860](https://www.openstreetmap.org/way/12231860) | State Street | residential | asphalt |
| Sumner Link Trail | 4139677459 | 47.215914 | -122.237557 | [12233103](https://www.openstreetmap.org/way/12233103) | Tacoma Avenue | tertiary | - |
| Sumner Link Trail | 110422923 | 47.203308 | -122.244243 | [12239164](https://www.openstreetmap.org/way/12239164) | Main Street | secondary | asphalt |
| Sumner Link Trail | 110933646 | 47.235523 | -122.233902 | [12245922](https://www.openstreetmap.org/way/12245922) | 24th Street East | residential | - |
| Sumner Link Trail | 110731683 | 47.211926 | -122.242194 | [25680623](https://www.openstreetmap.org/way/25680623) | 142nd Avenue East | tertiary | asphalt |
| Sumner Link Trail | 110422923 | 47.203308 | -122.244243 | [98689245](https://www.openstreetmap.org/way/98689245) | Bridge Street | secondary | asphalt |
| Sumner Link Trail | 110713098 | 47.203400 | -122.244529 | [98689245](https://www.openstreetmap.org/way/98689245) | Bridge Street | secondary | asphalt |
| Lake Tapps Parkway Trail | 2670829854 | 47.245316 | -122.209513 | [94176005](https://www.openstreetmap.org/way/94176005) | - | footway | - |
| Lake Tapps Parkway Trail | 2670829864 | 47.251611 | -122.227225 | [228599444](https://www.openstreetmap.org/way/228599444) | Terrace View Lane Southeast | residential | - |
| Lake Tapps Parkway Trail | 2670829819 | 47.251914 | -122.227747 | [228600161](https://www.openstreetmap.org/way/228600161) | Alexander Place Southeast | residential | - |
| Lake Tapps Parkway Trail | 6486204386 | 47.250200 | -122.234991 | [228600431](https://www.openstreetmap.org/way/228600431) | Stewart Road | secondary | asphalt |
| Lake Tapps Parkway Trail | 2670828792 | 47.249982 | -122.237497 | [261467897](https://www.openstreetmap.org/way/261467897) | Sumner Link Trail | cycleway | asphalt |
| Lake Tapps Parkway Trail | 2670829797 | 47.246752 | -122.223284 | [261468122](https://www.openstreetmap.org/way/261468122) | Vista Ridge Trail | cycleway | asphalt |
| Lake Tapps Parkway Trail | 6486202580 | 47.249987 | -122.238747 | [569456368](https://www.openstreetmap.org/way/569456368) | - | service | asphalt |
| Lake Tapps Parkway Trail | 6486202581 | 47.249988 | -122.237699 | [569456368](https://www.openstreetmap.org/way/569456368) | - | service | asphalt |
| Lake Tapps Parkway Trail | 9276360167 | 47.249991 | -122.241576 | [1005421361](https://www.openstreetmap.org/way/1005421361) | - | footway | asphalt |
| Lake Tapps Parkway Trail | 9276360167 | 47.249991 | -122.241576 | [1005421362](https://www.openstreetmap.org/way/1005421362) | - | footway | - |

### 30 m access points, aggregated per non-member road way

One row per (trail, road way). `trail nodes within 30 m` counts trail nodes inside the 30 m corridor; `closest` is the smallest distance; `shares node` says whether the reported closest trail node is an actual OSM junction with that road. Every entry is also in `islands.json` (`proximity_access_points`).

| trail | road way | road name | highway | surface | trail nodes within 30 m | closest (m) | closest trail node | shares a node somewhere on this road | closest sample is a junction |
|---|---|---|---|---|---|---|---|---|---|
| White River Trail | [6477377](https://www.openstreetmap.org/way/6477377) | Stuck River Drive | residential | asphalt | 12 | 5.4 | 2669377516 | **NO** (0 nodes) | no |
| White River Trail | [6510584](https://www.openstreetmap.org/way/6510584) | Oravetz Road Southeast | tertiary | - | 21 | 14.7 | 2669377513 | **NO** (0 nodes) | no |
| White River Trail | [25720485](https://www.openstreetmap.org/way/25720485) | R Street Southeast | tertiary | asphalt | 2 | 4.0 | 2669377524 | **NO** (0 nodes) | no |
| White River Trail | [354380326](https://www.openstreetmap.org/way/354380326) | - | track | - | 9 | 0.0 | 12594216248 | YES (1 nodes) | YES |
| White River Trail | [437531322](https://www.openstreetmap.org/way/437531322) | - | footway | asphalt | 5 | 0.0 | 4353441166 | YES (1 nodes) | YES |
| White River Trail | [437531327](https://www.openstreetmap.org/way/437531327) | - | footway | - | 3 | 0.0 | 2669377493 | YES (1 nodes) | YES |
| White River Trail | [461442379](https://www.openstreetmap.org/way/461442379) | - | footway | compacted | 5 | 0.0 | 4569347956 | YES (1 nodes) | YES |
| White River Trail | [674021791](https://www.openstreetmap.org/way/674021791) | - | cycleway | asphalt | 4 | 0.0 | 2669377522 | YES (1 nodes) | YES |
| White River Trail | [674021792](https://www.openstreetmap.org/way/674021792) | - | cycleway | - | 4 | 0.0 | 6312114902 | YES (1 nodes) | YES |
| White River Trail | [674202154](https://www.openstreetmap.org/way/674202154) | - | service | - | 1 | 0.0 | 2669374349 | YES (1 nodes) | YES |
| White River Trail | [845567702](https://www.openstreetmap.org/way/845567702) | - | cycleway | asphalt | 2 | 0.0 | 7888851531 | YES (1 nodes) | YES |
| White River Trail | [845567703](https://www.openstreetmap.org/way/845567703) | - | cycleway | asphalt | 2 | 0.0 | 2669377477 | YES (1 nodes) | YES |
| White River Trail | [845567705](https://www.openstreetmap.org/way/845567705) | - | cycleway | - | 2 | 0.0 | 10696070530 | YES (1 nodes) | YES |
| White River Trail | [845567706](https://www.openstreetmap.org/way/845567706) | - | cycleway | asphalt | 11 | 0.0 | 1094641378 | YES (2 nodes) | YES |
| White River Trail | [845567707](https://www.openstreetmap.org/way/845567707) | - | footway | compacted | 62 | 0.0 | 7888857334 | YES (1 nodes) | YES |
| White River Trail | [947658197](https://www.openstreetmap.org/way/947658197) | - | footway | - | 1 | 0.0 | 6313900974 | YES (1 nodes) | YES |
| White River Trail | [947658199](https://www.openstreetmap.org/way/947658199) | - | footway | asphalt | 1 | 0.0 | 8771695802 | YES (1 nodes) | YES |
| White River Trail | [947658200](https://www.openstreetmap.org/way/947658200) | - | footway | asphalt | 1 | 0.0 | 8771695805 | YES (1 nodes) | YES |
| White River Trail | [947658850](https://www.openstreetmap.org/way/947658850) | - | cycleway | asphalt | 4 | 0.0 | 8771675546 | YES (2 nodes) | YES |
| White River Trail | [1149786986](https://www.openstreetmap.org/way/1149786986) | - | footway | - | 1 | 0.0 | 10696070509 | YES (1 nodes) | YES |
| White River Trail | [1149786987](https://www.openstreetmap.org/way/1149786987) | - | footway | asphalt | 1 | 0.0 | 10696070514 | YES (1 nodes) | YES |
| White River Trail | [1149786989](https://www.openstreetmap.org/way/1149786989) | - | path | dirt | 13 | 0.0 | 10696070533 | YES (2 nodes) | YES |
| White River Trail | [1349703114](https://www.openstreetmap.org/way/1349703114) | - | footway | asphalt | 3 | 0.0 | 12485866145 | YES (1 nodes) | YES |
| White River Trail | [1392256034](https://www.openstreetmap.org/way/1392256034) | - | footway | - | 3 | 0.0 | 2669377511 | YES (1 nodes) | YES |
| White River Trail | [1392256035](https://www.openstreetmap.org/way/1392256035) | - | footway | - | 3 | 0.0 | 12887950499 | YES (1 nodes) | YES |
| White River Trail | [1392256036](https://www.openstreetmap.org/way/1392256036) | - | footway | - | 3 | 0.0 | 2669377479 | YES (1 nodes) | YES |
| White River Trail | [1452260028](https://www.openstreetmap.org/way/1452260028) | - | footway | - | 4 | 3.6 | 2669374361 | **NO** (0 nodes) | no |
| White River Trail | [1452260029](https://www.openstreetmap.org/way/1452260029) | - | footway | - | 3 | 3.8 | 2669374337 | **NO** (0 nodes) | no |
| White River Trail | [1528680020](https://www.openstreetmap.org/way/1528680020) | - | cycleway | - | 6 | 0.0 | 13930377804 | YES (2 nodes) | YES |
| Interurban Trail Milton | [6420938](https://www.openstreetmap.org/way/6420938) | South 380th Street | residential | asphalt | 3 | 0.0 | 53175521 | YES (1 nodes) | YES |
| Interurban Trail Milton | [12193441](https://www.openstreetmap.org/way/12193441) | Kent Street | residential | asphalt | 9 | 0.0 | 110480391 | YES (1 nodes) | YES |
| Interurban Trail Milton | [215897125](https://www.openstreetmap.org/way/215897125) | Wapato Way East | secondary | asphalt | 2 | 0.0 | 13174384557 | YES (1 nodes) | YES |
| Interurban Trail Milton | [245987923](https://www.openstreetmap.org/way/245987923) | 20th Street East | secondary | asphalt | 6 | 18.0 | 9352149151 | **NO** (0 nodes) | no |
| Interurban Trail Milton | [963101741](https://www.openstreetmap.org/way/963101741) | Wapato Way East | secondary | asphalt | 7 | 13.2 | 3213532073 | **NO** (0 nodes) | no |
| Interurban Trail Milton | [975539337](https://www.openstreetmap.org/way/975539337) | - | service | - | 3 | 14.8 | 110924198 | **NO** (0 nodes) | no |
| Interurban Trail Milton | [1013669076](https://www.openstreetmap.org/way/1013669076) | - | service | asphalt | 3 | 0.0 | 9352149140 | YES (1 nodes) | YES |
| Interurban Trail Milton | [1013669077](https://www.openstreetmap.org/way/1013669077) | Milton Interurban Trail | cycleway | asphalt | 7 | 0.0 | 9352149145 | YES (1 nodes) | YES |
| Interurban Trail Milton | [1215212095](https://www.openstreetmap.org/way/1215212095) | - | path | - | 3 | 0.0 | 11259007739 | YES (1 nodes) | YES |
| Interurban Trail Milton | [1215477816](https://www.openstreetmap.org/way/1215477816) | Milton Interurban Trail | cycleway | paved | 1 | 0.0 | 2379361679 | YES (1 nodes) | YES |
| Interurban Trail Milton | [1317769728](https://www.openstreetmap.org/way/1317769728) | - | service | asphalt | 3 | 0.0 | 12196160808 | YES (1 nodes) | YES |
| Interurban Trail Milton | [1331402721](https://www.openstreetmap.org/way/1331402721) | - | cycleway | concrete | 1 | 0.0 | 3213532066 | YES (1 nodes) | YES |
| Interurban Trail Milton | [1389229768](https://www.openstreetmap.org/way/1389229768) | - | path | - | 6 | 0.0 | 12860134678 | YES (2 nodes) | YES |
| Interurban Trail Milton | [1389229769](https://www.openstreetmap.org/way/1389229769) | - | path | - | 1 | 0.0 | 12860177113 | YES (1 nodes) | YES |
| Interurban Trail Milton | [1389229770](https://www.openstreetmap.org/way/1389229770) | - | footway | - | 2 | 0.0 | 12860177132 | YES (1 nodes) | YES |
| Interurban Trail Milton | [1445440192](https://www.openstreetmap.org/way/1445440192) | Porter Way | tertiary | asphalt | 10 | 0.0 | 110552160 | YES (1 nodes) | YES |
| Interurban Trail Edgewood | [50697841](https://www.openstreetmap.org/way/50697841) | Military Road East | secondary | asphalt | 1 | 0.0 | 645714471 | YES (1 nodes) | YES |
| Interurban Trail Edgewood | [259300196](https://www.openstreetmap.org/way/259300196) | - | service | compacted | 5 | 0.0 | 12882622451 | YES (1 nodes) | YES |
| Interurban Trail Edgewood | [765606369](https://www.openstreetmap.org/way/765606369) | 114th Avenue East | tertiary | asphalt | 3 | 0.0 | 2646868507 | YES (1 nodes) | YES |
| Interurban Trail Edgewood | [1116101456](https://www.openstreetmap.org/way/1116101456) | - | footway | asphalt | 1 | 24.2 | 12882604561 | **NO** (0 nodes) | no |
| Interurban Trail Edgewood | [1183624635](https://www.openstreetmap.org/way/1183624635) | 31st Avenue | residential | asphalt | 1 | 7.3 | 6496304994 | **NO** (0 nodes) | no |
| Interurban Trail Edgewood | [1391736645](https://www.openstreetmap.org/way/1391736645) | - | service | - | 3 | 0.0 | 12882604574 | YES (1 nodes) | YES |
| Riverwalk Trail | [12192692](https://www.openstreetmap.org/way/12192692) | River Road | primary | asphalt | 3 | 18.0 | 2101094383 | **NO** (0 nodes) | no |
| Riverwalk Trail | [12193756](https://www.openstreetmap.org/way/12193756) | 5th Avenue Northeast | residential | - | 1 | 0.0 | 6486202582 | YES (1 nodes) | YES |
| Riverwalk Trail | [12197659](https://www.openstreetmap.org/way/12197659) | 2nd Avenue Northeast | residential | - | 1 | 8.6 | 6486202538 | **NO** (0 nodes) | no |
| Riverwalk Trail | [12201578](https://www.openstreetmap.org/way/12201578) | 4th Avenue Northeast | residential | - | 1 | 0.0 | 6486202541 | YES (1 nodes) | YES |
| Riverwalk Trail | [12223099](https://www.openstreetmap.org/way/12223099) | 8th Avenue Northeast | residential | asphalt | 2 | 0.0 | 6486202546 | YES (1 nodes) | YES |
| Riverwalk Trail | [12226132](https://www.openstreetmap.org/way/12226132) | 7th Avenue Northeast | residential | - | 2 | 0.0 | 6486202544 | YES (1 nodes) | YES |
| Riverwalk Trail | [12235846](https://www.openstreetmap.org/way/12235846) | 7th Street Northeast | residential | concrete | 2 | 0.0 | 6486202537 | YES (1 nodes) | YES |
| Riverwalk Trail | [12236770](https://www.openstreetmap.org/way/12236770) | 5th Street Northeast | tertiary | asphalt | 3 | 0.0 | 6486202549 | YES (1 nodes) | YES |
| Riverwalk Trail | [12240663](https://www.openstreetmap.org/way/12240663) | 80th Avenue East | residential | - | 1 | 0.0 | 110880940 | YES (1 nodes) | YES |
| Riverwalk Trail | [23689624](https://www.openstreetmap.org/way/23689624) | - | motorway | concrete | 2 | 8.5 | 2101094327 | **NO** (0 nodes) | no |
| Riverwalk Trail | [23689625](https://www.openstreetmap.org/way/23689625) | - | motorway | asphalt | 4 | 4.4 | 2865478606 | **NO** (0 nodes) | no |
| Riverwalk Trail | [127788888](https://www.openstreetmap.org/way/127788888) | East Main | secondary | asphalt | 1 | 11.0 | 6486202533 | **NO** (0 nodes) | no |
| Riverwalk Trail | [200123036](https://www.openstreetmap.org/way/200123036) | - | service | - | 1 | 8.0 | 6273297985 | **NO** (0 nodes) | no |
| Riverwalk Trail | [200123038](https://www.openstreetmap.org/way/200123038) | - | cycleway | asphalt | 1 | 0.0 | 2101094375 | YES (1 nodes) | YES |
| Riverwalk Trail | [249317219](https://www.openstreetmap.org/way/249317219) | - | service | - | 1 | 22.1 | 2657474748 | **NO** (0 nodes) | no |
| Riverwalk Trail | [249847275](https://www.openstreetmap.org/way/249847275) | - | service | asphalt | 1 | 27.9 | 2101094371 | **NO** (0 nodes) | no |
| Riverwalk Trail | [260145143](https://www.openstreetmap.org/way/260145143) | - | service | asphalt | 5 | 10.8 | 11836115758 | **NO** (0 nodes) | no |
| Riverwalk Trail | [263722022](https://www.openstreetmap.org/way/263722022) | - | service | paved | 4 | 10.7 | 12953091085 | **NO** (0 nodes) | no |
| Riverwalk Trail | [264069270](https://www.openstreetmap.org/way/264069270) | - | service | paved | 1 | 23.2 | 8581013163 | **NO** (0 nodes) | no |
| Riverwalk Trail | [264069278](https://www.openstreetmap.org/way/264069278) | - | service | paved | 3 | 10.3 | 2101094376 | **NO** (0 nodes) | no |
| Riverwalk Trail | [264069289](https://www.openstreetmap.org/way/264069289) | - | service | paved | 6 | 12.9 | 2697362271 | **NO** (0 nodes) | no |
| Riverwalk Trail | [282578108](https://www.openstreetmap.org/way/282578108) | - | service | - | 1 | 0.0 | 6486202535 | YES (1 nodes) | YES |
| Riverwalk Trail | [356690848](https://www.openstreetmap.org/way/356690848) | - | path | asphalt | 3 | 0.0 | 3621050859 | YES (1 nodes) | YES |
| Riverwalk Trail | [367554393](https://www.openstreetmap.org/way/367554393) | - | cycleway | concrete | 1 | 0.0 | 2101094348 | YES (1 nodes) | YES |
| Riverwalk Trail | [385206998](https://www.openstreetmap.org/way/385206998) | North Meridian | primary | asphalt | 1 | 4.8 | 11836115757 | **NO** (0 nodes) | no |
| Riverwalk Trail | [385206999](https://www.openstreetmap.org/way/385206999) | North Meridian | primary | concrete | 1 | 2.3 | 2657474743 | **NO** (0 nodes) | no |
| Riverwalk Trail | [557149439](https://www.openstreetmap.org/way/557149439) | Riverwalk Trail | cycleway | asphalt | 1 | 0.0 | 5373690093 | YES (1 nodes) | YES |
| Riverwalk Trail | [564471185](https://www.openstreetmap.org/way/564471185) | - | service | asphalt | 1 | 0.0 | 5438641965 | YES (1 nodes) | YES |
| Riverwalk Trail | [564471188](https://www.openstreetmap.org/way/564471188) | - | footway | - | 2 | 0.0 | 5438641962 | YES (1 nodes) | YES |
| Riverwalk Trail | [567651111](https://www.openstreetmap.org/way/567651111) | - | service | - | 7 | 6.9 | 2101094322 | **NO** (0 nodes) | no |
| Riverwalk Trail | [567651112](https://www.openstreetmap.org/way/567651112) | - | service | - | 6 | 18.1 | 2101094332 | **NO** (0 nodes) | no |
| Riverwalk Trail | [569758579](https://www.openstreetmap.org/way/569758579) | - | service | - | 1 | 7.0 | 2865478604 | **NO** (0 nodes) | no |
| Riverwalk Trail | [569758697](https://www.openstreetmap.org/way/569758697) | - | service | - | 2 | 22.4 | 2101094374 | **NO** (0 nodes) | no |
| Riverwalk Trail | [665421719](https://www.openstreetmap.org/way/665421719) | - | footway | - | 2 | 0.0 | 5438641963 | YES (1 nodes) | YES |
| Riverwalk Trail | [670339893](https://www.openstreetmap.org/way/670339893) | - | cycleway | asphalt | 2 | 0.0 | 6277555604 | YES (1 nodes) | YES |
| Riverwalk Trail | [670339923](https://www.openstreetmap.org/way/670339923) | - | footway | - | 2 | 0.0 | 6277555796 | YES (1 nodes) | YES |
| Riverwalk Trail | [691257975](https://www.openstreetmap.org/way/691257975) | Sumner Link Trail | cycleway | concrete | 1 | 0.0 | 6486202530 | YES (1 nodes) | YES |
| Riverwalk Trail | [700893947](https://www.openstreetmap.org/way/700893947) | - | cycleway | asphalt | 5 | 0.0 | 3624199042 | YES (1 nodes) | YES |
| Riverwalk Trail | [824491849](https://www.openstreetmap.org/way/824491849) | - | service | - | 1 | 0.0 | 7698794632 | YES (1 nodes) | YES |
| Riverwalk Trail | [855234992](https://www.openstreetmap.org/way/855234992) | - | service | - | 1 | 0.0 | 7975500106 | YES (1 nodes) | YES |
| Riverwalk Trail | [924569906](https://www.openstreetmap.org/way/924569906) | - | cycleway | asphalt | 2 | 0.0 | 8581013159 | YES (1 nodes) | YES |
| Riverwalk Trail | [1000417053](https://www.openstreetmap.org/way/1000417053) | - | path | - | 4 | 0.0 | 2697362282 | YES (1 nodes) | YES |
| Riverwalk Trail | [1196648594](https://www.openstreetmap.org/way/1196648594) | - | footway | - | 2 | 0.0 | 11103509870 | YES (1 nodes) | YES |
| Riverwalk Trail | [1196648595](https://www.openstreetmap.org/way/1196648595) | - | footway | - | 2 | 0.0 | 11103509871 | YES (1 nodes) | YES |
| Riverwalk Trail | [1196648616](https://www.openstreetmap.org/way/1196648616) | - | footway | - | 1 | 0.0 | 11103509934 | YES (1 nodes) | YES |
| Riverwalk Trail | [1196648623](https://www.openstreetmap.org/way/1196648623) | - | footway | - | 1 | 0.0 | 6486202540 | YES (1 nodes) | YES |
| Riverwalk Trail | [1299698538](https://www.openstreetmap.org/way/1299698538) | - | path | - | 3 | 0.0 | 2101094355 | YES (2 nodes) | YES |
| Riverwalk Trail | [1299926513](https://www.openstreetmap.org/way/1299926513) | - | footway | - | 2 | 0.0 | 6486202532 | YES (1 nodes) | YES |
| Riverwalk Trail | [1354825219](https://www.openstreetmap.org/way/1354825219) | River Road East | primary | asphalt | 1 | 12.2 | 2101094390 | **NO** (0 nodes) | no |
| Riverwalk Trail | [1355135266](https://www.openstreetmap.org/way/1355135266) | River Road | primary | asphalt | 3 | 22.1 | 2101094388 | **NO** (0 nodes) | no |
| Riverwalk Trail | [1355135267](https://www.openstreetmap.org/way/1355135267) | River Road | primary | asphalt | 1 | 22.9 | 2101094387 | **NO** (0 nodes) | no |
| Riverwalk Trail | [1383899794](https://www.openstreetmap.org/way/1383899794) | - | path | - | 7 | 0.0 | 5438641960 | YES (1 nodes) | YES |
| Riverwalk Trail | [1384462577](https://www.openstreetmap.org/way/1384462577) | - | service | - | 2 | 28.3 | 8581013162 | **NO** (0 nodes) | no |
| Riverwalk Trail | [1409644390](https://www.openstreetmap.org/way/1409644390) | - | cycleway | - | 5 | 0.0 | 12953104606 | YES (1 nodes) | YES |
| Riverwalk Trail | [1409644402](https://www.openstreetmap.org/way/1409644402) | - | cycleway | - | 1 | 0.0 | 12953104703 | YES (1 nodes) | YES |
| Riverwalk Trail | [1417435503](https://www.openstreetmap.org/way/1417435503) | - | cycleway | - | 2 | 0.0 | 13025760409 | YES (1 nodes) | YES |
| Riverwalk Trail | [1432074938](https://www.openstreetmap.org/way/1432074938) | - | service | paved | 1 | 8.5 | 2865381057 | **NO** (0 nodes) | no |
| Riverwalk Trail | [1433885400](https://www.openstreetmap.org/way/1433885400) | - | cycleway | asphalt | 1 | 0.0 | 2101094341 | YES (1 nodes) | YES |
| Riverwalk Trail | [1495985749](https://www.openstreetmap.org/way/1495985749) | - | footway | concrete | 1 | 5.8 | 2101094369 | **NO** (0 nodes) | no |
| Sumner Link Trail | [12182723](https://www.openstreetmap.org/way/12182723) | Fryar Avenue | tertiary | asphalt | 4 | 0.0 | 5479081486 | YES (1 nodes) | YES |
| Sumner Link Trail | [12183068](https://www.openstreetmap.org/way/12183068) | 57th Street East | residential | asphalt | 1 | 0.0 | 110422926 | YES (1 nodes) | YES |
| Sumner Link Trail | [12229434](https://www.openstreetmap.org/way/12229434) | 63rd Street East | residential | - | 5 | 9.8 | 2670806289 | **NO** (0 nodes) | no |
| Sumner Link Trail | [12231716](https://www.openstreetmap.org/way/12231716) | Zehnder Street | tertiary | asphalt | 1 | 0.0 | 110422931 | YES (1 nodes) | YES |
| Sumner Link Trail | [12231860](https://www.openstreetmap.org/way/12231860) | State Street | residential | asphalt | 9 | 0.0 | 110790321 | YES (1 nodes) | YES |
| Sumner Link Trail | [12233103](https://www.openstreetmap.org/way/12233103) | Tacoma Avenue | tertiary | - | 1 | 0.0 | 4139677459 | YES (1 nodes) | YES |
| Sumner Link Trail | [12235089](https://www.openstreetmap.org/way/12235089) | Spinning Avenue | residential | asphalt | 1 | 24.5 | 3730012152 | **NO** (0 nodes) | no |
| Sumner Link Trail | [12239159](https://www.openstreetmap.org/way/12239159) | West Main Street | residential | asphalt | 6 | 2.2 | 6486202550 | **NO** (0 nodes) | no |
| Sumner Link Trail | [12239164](https://www.openstreetmap.org/way/12239164) | Main Street | secondary | asphalt | 1 | 0.0 | 110422923 | YES (1 nodes) | YES |
| Sumner Link Trail | [12242264](https://www.openstreetmap.org/way/12242264) | East Main | secondary | concrete | 1 | 11.6 | 6486202534 | **NO** (0 nodes) | no |
| Sumner Link Trail | [12245922](https://www.openstreetmap.org/way/12245922) | 24th Street East | residential | - | 1 | 0.0 | 110933646 | YES (1 nodes) | YES |
| Sumner Link Trail | [25680605](https://www.openstreetmap.org/way/25680605) | Tacoma Avenue | tertiary | - | 4 | 9.6 | 6486202577 | **NO** (0 nodes) | no |
| Sumner Link Trail | [25680606](https://www.openstreetmap.org/way/25680606) | Tacoma Avenue | tertiary | - | 3 | 9.8 | 6486202578 | **NO** (0 nodes) | no |
| Sumner Link Trail | [25680622](https://www.openstreetmap.org/way/25680622) | 142nd Avenue East | tertiary | asphalt | 1 | 3.9 | 6486202568 | **NO** (0 nodes) | no |
| Sumner Link Trail | [25680623](https://www.openstreetmap.org/way/25680623) | 142nd Avenue East | tertiary | asphalt | 3 | 0.0 | 110731683 | YES (1 nodes) | YES |
| Sumner Link Trail | [98689245](https://www.openstreetmap.org/way/98689245) | Bridge Street | secondary | asphalt | 2 | 0.0 | 110713098 | YES (2 nodes) | YES |
| Sumner Link Trail | [261467073](https://www.openstreetmap.org/way/261467073) | - | footway | asphalt | 3 | 0.0 | 2670823453 | YES (1 nodes) | YES |
| Sumner Link Trail | [261467074](https://www.openstreetmap.org/way/261467074) | - | cycleway | asphalt | 4 | 0.0 | 8848928542 | YES (1 nodes) | YES |
| Sumner Link Trail | [261467894](https://www.openstreetmap.org/way/261467894) | - | cycleway | asphalt | 6 | 0.0 | 2670828304 | YES (1 nodes) | YES |
| Sumner Link Trail | [261467941](https://www.openstreetmap.org/way/261467941) | Lake Tapps Parkway Trail | cycleway | concrete | 1 | 0.0 | 2670828792 | YES (1 nodes) | YES |
| Sumner Link Trail | [283702114](https://www.openstreetmap.org/way/283702114) | 142nd Avenue East | tertiary | asphalt | 6 | 2.0 | 6486202569 | **NO** (0 nodes) | no |
| Sumner Link Trail | [419747833](https://www.openstreetmap.org/way/419747833) | - | cycleway | - | 7 | 0.0 | 2670823369 | YES (1 nodes) | YES |
| Sumner Link Trail | [451051335](https://www.openstreetmap.org/way/451051335) | 150th Avenue Court East | residential | - | 2 | 9.4 | 2670823452 | **NO** (0 nodes) | no |
| Sumner Link Trail | [542210485](https://www.openstreetmap.org/way/542210485) | - | service | - | 2 | 0.0 | 5242303641 | YES (1 nodes) | YES |
| Sumner Link Trail | [542210490](https://www.openstreetmap.org/way/542210490) | - | service | - | 1 | 0.0 | 6486202552 | YES (1 nodes) | YES |
| Sumner Link Trail | [542210508](https://www.openstreetmap.org/way/542210508) | - | service | - | 2 | 0.0 | 5242303703 | YES (2 nodes) | YES |
| Sumner Link Trail | [542210509](https://www.openstreetmap.org/way/542210509) | - | service | - | 1 | 0.0 | 5242303710 | YES (1 nodes) | YES |
| Sumner Link Trail | [542210524](https://www.openstreetmap.org/way/542210524) | - | service | - | 1 | 0.0 | 5242303776 | YES (1 nodes) | YES |
| Sumner Link Trail | [569456368](https://www.openstreetmap.org/way/569456368) | - | service | asphalt | 7 | 12.7 | 2670828243 | **NO** (0 nodes) | no |
| Sumner Link Trail | [569772368](https://www.openstreetmap.org/way/569772368) | - | service | - | 2 | 10.6 | 3448697795 | **NO** (0 nodes) | no |
| Sumner Link Trail | [569775179](https://www.openstreetmap.org/way/569775179) | - | service | - | 9 | 12.4 | 2670823331 | **NO** (0 nodes) | no |
| Sumner Link Trail | [569775191](https://www.openstreetmap.org/way/569775191) | - | service | - | 3 | 13.6 | 2670823445 | **NO** (0 nodes) | no |
| Sumner Link Trail | [569775219](https://www.openstreetmap.org/way/569775219) | - | service | - | 2 | 22.7 | 2670823393 | **NO** (0 nodes) | no |
| Sumner Link Trail | [569775220](https://www.openstreetmap.org/way/569775220) | - | service | - | 3 | 8.9 | 4197511739 | **NO** (0 nodes) | no |
| Sumner Link Trail | [569775228](https://www.openstreetmap.org/way/569775228) | - | service | - | 2 | 28.8 | 2670823412 | **NO** (0 nodes) | no |
| Sumner Link Trail | [569775237](https://www.openstreetmap.org/way/569775237) | - | service | - | 5 | 0.0 | 5479081416 | YES (1 nodes) | YES |
| Sumner Link Trail | [569775238](https://www.openstreetmap.org/way/569775238) | - | service | - | 1 | 27.3 | 2670823392 | **NO** (0 nodes) | no |
| Sumner Link Trail | [569775239](https://www.openstreetmap.org/way/569775239) | - | service | - | 6 | 14.6 | 2670823404 | **NO** (0 nodes) | no |
| Sumner Link Trail | [569775256](https://www.openstreetmap.org/way/569775256) | - | service | - | 3 | 0.0 | 5479081485 | YES (2 nodes) | YES |
| Sumner Link Trail | [569775257](https://www.openstreetmap.org/way/569775257) | - | service | - | 1 | 0.0 | 5479081494 | YES (1 nodes) | YES |
| Sumner Link Trail | [569775258](https://www.openstreetmap.org/way/569775258) | - | service | - | 2 | 0.0 | 5479081499 | YES (2 nodes) | YES |
| Sumner Link Trail | [569776719](https://www.openstreetmap.org/way/569776719) | - | service | asphalt | 1 | 0.0 | 5479094359 | YES (1 nodes) | YES |
| Sumner Link Trail | [647719589](https://www.openstreetmap.org/way/647719589) | - | service | - | 2 | 0.0 | 6486202555 | YES (2 nodes) | YES |
| Sumner Link Trail | [691257977](https://www.openstreetmap.org/way/691257977) | Riverwalk Trail | cycleway | concrete | 1 | 0.0 | 6486202530 | YES (1 nodes) | YES |
| Sumner Link Trail | [691257989](https://www.openstreetmap.org/way/691257989) | - | cycleway | concrete | 4 | 0.0 | 2670824385 | YES (1 nodes) | YES |
| Sumner Link Trail | [759077942](https://www.openstreetmap.org/way/759077942) | - | footway | - | 3 | 5.1 | 8251417554 | **NO** (0 nodes) | no |
| Sumner Link Trail | [759077944](https://www.openstreetmap.org/way/759077944) | - | footway | asphalt | 1 | 0.0 | 7089980148 | YES (1 nodes) | YES |
| Sumner Link Trail | [759077954](https://www.openstreetmap.org/way/759077954) | - | footway | - | 4 | 0.0 | 7089994023 | YES (1 nodes) | YES |
| Sumner Link Trail | [759079444](https://www.openstreetmap.org/way/759079444) | - | footway | - | 2 | 0.0 | 6486202561 | YES (1 nodes) | YES |
| Sumner Link Trail | [759079445](https://www.openstreetmap.org/way/759079445) | - | footway | - | 3 | 0.0 | 7090006590 | YES (1 nodes) | YES |
| Sumner Link Trail | [759079451](https://www.openstreetmap.org/way/759079451) | Sumner Link Trail | footway | - | 7 | 0.0 | 7090006594 | YES (1 nodes) | YES |
| Sumner Link Trail | [941156765](https://www.openstreetmap.org/way/941156765) | - | footway | asphalt | 6 | 0.0 | 8716240477 | YES (1 nodes) | YES |
| Sumner Link Trail | [941156768](https://www.openstreetmap.org/way/941156768) | - | footway | gravel | 5 | 0.0 | 2670823436 | YES (2 nodes) | YES |
| Sumner Link Trail | [956118281](https://www.openstreetmap.org/way/956118281) | 142nd Avenue East | tertiary | asphalt | 2 | 27.6 | 2670823421 | **NO** (0 nodes) | no |
| Sumner Link Trail | [956118283](https://www.openstreetmap.org/way/956118283) | - | footway | - | 2 | 0.0 | 8848922806 | YES (1 nodes) | YES |
| Sumner Link Trail | [956118286](https://www.openstreetmap.org/way/956118286) | - | footway | - | 1 | 0.0 | 8848922808 | YES (1 nodes) | YES |
| Sumner Link Trail | [956118288](https://www.openstreetmap.org/way/956118288) | - | path | - | 1 | 0.0 | 4479060451 | YES (1 nodes) | YES |
| Sumner Link Trail | [975594672](https://www.openstreetmap.org/way/975594672) | - | service | - | 1 | 0.0 | 7181596297 | YES (1 nodes) | YES |
| Sumner Link Trail | [1004594385](https://www.openstreetmap.org/way/1004594385) | - | footway | - | 2 | 0.0 | 6486202558 | YES (1 nodes) | YES |
| Sumner Link Trail | [1004594396](https://www.openstreetmap.org/way/1004594396) | - | footway | - | 3 | 0.0 | 6486202559 | YES (1 nodes) | YES |
| Sumner Link Trail | [1005421275](https://www.openstreetmap.org/way/1005421275) | - | footway | - | 14 | 0.0 | 2670823424 | YES (1 nodes) | YES |
| Sumner Link Trail | [1005421278](https://www.openstreetmap.org/way/1005421278) | - | footway | - | 1 | 0.0 | 4139677458 | YES (1 nodes) | YES |
| Sumner Link Trail | [1005421280](https://www.openstreetmap.org/way/1005421280) | - | cycleway | asphalt | 5 | 0.0 | 2670823346 | YES (1 nodes) | YES |
| Sumner Link Trail | [1005421281](https://www.openstreetmap.org/way/1005421281) | - | footway | concrete | 9 | 0.0 | 9276359584 | YES (1 nodes) | YES |
| Sumner Link Trail | [1005421387](https://www.openstreetmap.org/way/1005421387) | - | footway | - | 3 | 0.0 | 9276360431 | YES (1 nodes) | YES |
| Sumner Link Trail | [1005421388](https://www.openstreetmap.org/way/1005421388) | - | footway | - | 4 | 2.8 | 2670823389 | **NO** (0 nodes) | no |
| Sumner Link Trail | [1005421396](https://www.openstreetmap.org/way/1005421396) | - | footway | - | 3 | 0.0 | 9276360473 | YES (1 nodes) | YES |
| Sumner Link Trail | [1005421397](https://www.openstreetmap.org/way/1005421397) | - | footway | - | 4 | 0.0 | 2670823476 | YES (1 nodes) | YES |
| Sumner Link Trail | [1043294945](https://www.openstreetmap.org/way/1043294945) | - | cycleway | - | 1 | 0.0 | 2670806303 | YES (1 nodes) | YES |
| Sumner Link Trail | [1173169492](https://www.openstreetmap.org/way/1173169492) | - | path | - | 8 | 0.0 | 10900530361 | YES (1 nodes) | YES |
| Sumner Link Trail | [1173169493](https://www.openstreetmap.org/way/1173169493) | - | path | grass | 5 | 0.0 | 10900530365 | YES (1 nodes) | YES |
| Sumner Link Trail | [1297893046](https://www.openstreetmap.org/way/1297893046) | - | path | unpaved | 7 | 0.0 | 12024343323 | YES (2 nodes) | YES |
| Sumner Link Trail | [1297893047](https://www.openstreetmap.org/way/1297893047) | - | path | unpaved | 7 | 0.0 | 3448697484 | YES (2 nodes) | YES |
| Sumner Link Trail | [1297893053](https://www.openstreetmap.org/way/1297893053) | - | path | dirt | 2 | 0.0 | 12024618625 | YES (1 nodes) | YES |
| Sumner Link Trail | [1297893059](https://www.openstreetmap.org/way/1297893059) | - | path | grass | 7 | 0.0 | 9276360140 | YES (1 nodes) | YES |
| Sumner Link Trail | [1297893061](https://www.openstreetmap.org/way/1297893061) | - | path | grass | 5 | 0.0 | 12024618716 | YES (1 nodes) | YES |
| Sumner Link Trail | [1297893064](https://www.openstreetmap.org/way/1297893064) | - | path | - | 6 | 0.0 | 12024618771 | YES (1 nodes) | YES |
| Sumner Link Trail | [1433970034](https://www.openstreetmap.org/way/1433970034) | - | footway | - | 2 | 7.2 | 110422933 | **NO** (0 nodes) | no |
| Sumner Link Trail | [1437032024](https://www.openstreetmap.org/way/1437032024) | Stewart Road | secondary | asphalt | 1 | 0.0 | 2670828294 | YES (1 nodes) | YES |
| Sumner Link Trail | [1466144144](https://www.openstreetmap.org/way/1466144144) | - | service | - | 1 | 0.0 | 5242303626 | YES (1 nodes) | YES |
| Sumner Link Trail | [1466144146](https://www.openstreetmap.org/way/1466144146) | - | service | - | 1 | 0.0 | 13447458479 | YES (1 nodes) | YES |
| Sumner Link Trail | [1466144147](https://www.openstreetmap.org/way/1466144147) | - | service | - | 1 | 0.0 | 13447458484 | YES (1 nodes) | YES |
| Lake Tapps Parkway Trail | [25684458](https://www.openstreetmap.org/way/25684458) | - | tertiary_link | asphalt | 9 | 2.4 | 2670829835 | **NO** (0 nodes) | no |
| Lake Tapps Parkway Trail | [94176005](https://www.openstreetmap.org/way/94176005) | - | footway | - | 1 | 0.0 | 2670829854 | YES (1 nodes) | YES |
| Lake Tapps Parkway Trail | [94176350](https://www.openstreetmap.org/way/94176350) | - | footway | - | 1 | 5.7 | 2670829833 | **NO** (0 nodes) | no |
| Lake Tapps Parkway Trail | [228599444](https://www.openstreetmap.org/way/228599444) | Terrace View Lane Southeast | residential | - | 1 | 0.0 | 2670829864 | YES (1 nodes) | YES |
| Lake Tapps Parkway Trail | [228600161](https://www.openstreetmap.org/way/228600161) | Alexander Place Southeast | residential | - | 2 | 0.0 | 2670829819 | YES (1 nodes) | YES |
| Lake Tapps Parkway Trail | [228600431](https://www.openstreetmap.org/way/228600431) | Stewart Road | secondary | asphalt | 5 | 0.0 | 6486204386 | YES (1 nodes) | YES |
| Lake Tapps Parkway Trail | [261467897](https://www.openstreetmap.org/way/261467897) | Sumner Link Trail | cycleway | asphalt | 1 | 0.0 | 2670828792 | YES (1 nodes) | YES |
| Lake Tapps Parkway Trail | [261468122](https://www.openstreetmap.org/way/261468122) | Vista Ridge Trail | cycleway | asphalt | 1 | 0.0 | 2670829797 | YES (1 nodes) | YES |
| Lake Tapps Parkway Trail | [569456368](https://www.openstreetmap.org/way/569456368) | - | service | asphalt | 2 | 0.0 | 6486202580 | YES (2 nodes) | YES |
| Lake Tapps Parkway Trail | [618805563](https://www.openstreetmap.org/way/618805563) | Lake Tapps Parkway Southeast | secondary | asphalt | 1 | 12.2 | 2670829774 | **NO** (0 nodes) | no |
| Lake Tapps Parkway Trail | [618805568](https://www.openstreetmap.org/way/618805568) | Lake Tapps Parkway Southeast | secondary | asphalt | 22 | 5.8 | 2670829840 | **NO** (0 nodes) | no |
| Lake Tapps Parkway Trail | [635794765](https://www.openstreetmap.org/way/635794765) | Terrace View Drive | tertiary | - | 15 | 4.0 | 2670829873 | **NO** (0 nodes) | no |
| Lake Tapps Parkway Trail | [737726804](https://www.openstreetmap.org/way/737726804) | Lake Tapps Parkway Southeast | secondary | asphalt | 3 | 11.7 | 2670829887 | **NO** (0 nodes) | no |
| Lake Tapps Parkway Trail | [1005421361](https://www.openstreetmap.org/way/1005421361) | - | footway | asphalt | 1 | 0.0 | 9276360167 | YES (1 nodes) | YES |
| Lake Tapps Parkway Trail | [1005421367](https://www.openstreetmap.org/way/1005421367) | - | footway | asphalt | 1 | 0.0 | 9276360175 | YES (1 nodes) | YES |
| Lake Tapps Parkway Trail | [1005421369](https://www.openstreetmap.org/way/1005421369) | - | footway | asphalt | 1 | 0.0 | 9276360222 | YES (1 nodes) | YES |
| Lake Tapps Parkway Trail | [1005421370](https://www.openstreetmap.org/way/1005421370) | - | footway | - | 5 | 0.0 | 9276360227 | YES (1 nodes) | YES |
| Lake Tapps Parkway Trail | [1173169494](https://www.openstreetmap.org/way/1173169494) | - | path | - | 2 | 0.0 | 10900530370 | YES (1 nodes) | YES |
| Lake Tapps Parkway Trail | [1297725058](https://www.openstreetmap.org/way/1297725058) | - | path | dirt | 3 | 0.0 | 12023213885 | YES (2 nodes) | YES |
| Lake Tapps Parkway Trail | [1326182522](https://www.openstreetmap.org/way/1326182522) | East Valley Highway East | secondary | - | 5 | 0.0 | 2670829813 | YES (1 nodes) | YES |
| Lake Tapps Parkway Trail | [1326182523](https://www.openstreetmap.org/way/1326182523) | Lake Tapps Parkway Southeast | secondary | asphalt | 1 | 7.0 | 2670829821 | **NO** (0 nodes) | no |
| Lake Tapps Parkway Trail | [1437032022](https://www.openstreetmap.org/way/1437032022) | Stewart Road | secondary | asphalt | 1 | 10.2 | 2670828810 | **NO** (0 nodes) | no |
| Lake Tapps Parkway Trail | [1437032023](https://www.openstreetmap.org/way/1437032023) | Stewart Road | secondary | asphalt | 1 | 11.5 | 9276360176 | **NO** (0 nodes) | no |
| Lake Tapps Parkway Trail | [1479596175](https://www.openstreetmap.org/way/1479596175) | - | footway | - | 1 | 0.0 | 13572479015 | YES (1 nodes) | YES |

Per-trail summary of the 30 m corridor:

* **White River Trail**: 29 road ways within 30 m; 24 with at least one shared node (routable junction); 5 reached by proximity only, involving 164 trail nodes.
* **Interurban Trail Milton**: 16 road ways within 30 m; 13 with at least one shared node (routable junction); 3 reached by proximity only, involving 53 trail nodes.
* **Interurban Trail Edgewood**: 6 road ways within 30 m; 4 with at least one shared node (routable junction); 2 reached by proximity only, involving 10 trail nodes.
* **Riverwalk Trail**: 59 road ways within 30 m; 34 with at least one shared node (routable junction); 25 reached by proximity only, involving 94 trail nodes.
* **Sumner Link Trail**: 82 road ways within 30 m; 60 with at least one shared node (routable junction); 22 reached by proximity only, involving 206 trail nodes.
* **Lake Tapps Parkway Trail**: 24 road ways within 30 m; 15 with at least one shared node (routable junction); 9 reached by proximity only, involving 69 trail nodes.

### Mid-segment residue (interior within 30 m while neither segment endpoint is)

| trail | trail way | sample | lat | lon | road way | road name | highway | dist (m) | shares node |
|---|---|---|---|---|---|---|---|---|---|
| White River Trail | - | segment mid t=0.75 | 47.282924 | -122.192959 | [354380326](https://www.openstreetmap.org/way/354380326) | - | track | 23.2 | no |
| White River Trail | - | segment mid t=0.75 | 47.281352 | -122.196726 | [1392256035](https://www.openstreetmap.org/way/1392256035) | - | footway | 22.3 | no |
| White River Trail | - | segment mid t=0.25 | 47.281115 | -122.197029 | [1392256035](https://www.openstreetmap.org/way/1392256035) | - | footway | 12.6 | no |
| White River Trail | - | segment mid t=0.25 | 47.278861 | -122.198407 | [437531322](https://www.openstreetmap.org/way/437531322) | - | footway | 25.1 | no |
| White River Trail | - | segment mid t=0.25 | 47.274412 | -122.209033 | [674021792](https://www.openstreetmap.org/way/674021792) | - | cycleway | 20.2 | no |
| White River Trail | - | segment mid t=0.75 | 47.274342 | -122.210057 | [1528680020](https://www.openstreetmap.org/way/1528680020) | - | cycleway | 12.9 | no |
| White River Trail | - | segment mid t=0.25 | 47.274259 | -122.210858 | [1528680020](https://www.openstreetmap.org/way/1528680020) | - | cycleway | 27.1 | no |
| White River Trail | - | segment mid t=0.75 | 47.274138 | -122.211291 | [6510584](https://www.openstreetmap.org/way/6510584) | Oravetz Road Southeast | tertiary | 29.6 | no |
| White River Trail | - | segment mid t=0.25 | 47.268218 | -122.223694 | [845567707](https://www.openstreetmap.org/way/845567707) | - | footway | 25.8 | no |
| White River Trail | - | segment mid t=0.75 | 47.267710 | -122.224000 | [845567707](https://www.openstreetmap.org/way/845567707) | - | footway | 29.4 | no |
| White River Trail | - | segment mid t=0.75 | 47.265752 | -122.229367 | [1452260028](https://www.openstreetmap.org/way/1452260028) | - | footway | 17.0 | no |
| Interurban Trail Milton | - | segment mid t=0.75 | 47.246720 | -122.325609 | [975539337](https://www.openstreetmap.org/way/975539337) | - | service | 27.2 | no |
| Interurban Trail Milton | - | segment mid t=0.25 | 47.248285 | -122.324468 | [1445440192](https://www.openstreetmap.org/way/1445440192) | Porter Way | tertiary | 17.3 | no |
| Interurban Trail Milton | - | segment mid t=0.25 | 47.250103 | -122.323231 | [12193441](https://www.openstreetmap.org/way/12193441) | Kent Street | residential | 20.4 | no |
| Interurban Trail Milton | - | segment mid t=0.75 | 47.254298 | -122.323577 | [1389229768](https://www.openstreetmap.org/way/1389229768) | - | path | 26.4 | no |
| Interurban Trail Milton | - | segment mid t=0.25 | 47.254706 | -122.323368 | [1389229768](https://www.openstreetmap.org/way/1389229768) | - | path | 20.5 | no |
| Interurban Trail Milton | - | segment mid t=0.75 | 47.255061 | -122.322965 | [1389229768](https://www.openstreetmap.org/way/1389229768) | - | path | 25.4 | no |
| Interurban Trail Milton | - | segment mid t=0.25 | 47.255314 | -122.322422 | [1389229768](https://www.openstreetmap.org/way/1389229768) | - | path | 24.4 | no |
| Interurban Trail Milton | - | segment mid t=0.75 | 47.257892 | -122.316108 | [1389229769](https://www.openstreetmap.org/way/1389229769) | - | path | 12.3 | no |
| Interurban Trail Milton | - | segment mid t=0.25 | 47.258145 | -122.315756 | [1389229769](https://www.openstreetmap.org/way/1389229769) | - | path | 17.6 | no |
| Interurban Trail Milton | - | segment mid t=0.75 | 47.261812 | -122.310973 | [1389229770](https://www.openstreetmap.org/way/1389229770) | - | footway | 8.4 | no |
| Interurban Trail Milton | - | segment mid t=0.25 | 47.262118 | -122.310708 | [1389229770](https://www.openstreetmap.org/way/1389229770) | - | footway | 24.3 | no |
| Interurban Trail Milton | - | segment mid t=0.75 | 47.261578 | -122.301196 | [6420938](https://www.openstreetmap.org/way/6420938) | South 380th Street | residential | 27.3 | no |
| Interurban Trail Edgewood | - | segment mid t=0.25 | 47.258161 | -122.292837 | [259300196](https://www.openstreetmap.org/way/259300196) | - | service | 29.4 | no |
| Riverwalk Trail | - | segment mid t=0.75 | 47.198311 | -122.256015 | [1299698538](https://www.openstreetmap.org/way/1299698538) | - | path | 22.9 | no |
| Riverwalk Trail | - | segment mid t=0.75 | 47.197179 | -122.253020 | [1299698538](https://www.openstreetmap.org/way/1299698538) | - | path | 24.7 | no |
| Riverwalk Trail | - | segment mid t=0.25 | 47.193980 | -122.276521 | [1409644390](https://www.openstreetmap.org/way/1409644390) | - | cycleway | 14.6 | no |
| Riverwalk Trail | - | segment mid t=0.25 | 47.193190 | -122.279723 | [567651112](https://www.openstreetmap.org/way/567651112) | - | service | 26.2 | no |
| Riverwalk Trail | - | segment mid t=0.75 | 47.193553 | -122.277734 | [263722022](https://www.openstreetmap.org/way/263722022) | - | service | 30.0 | no |
| Riverwalk Trail | - | segment mid t=0.25 | 47.206866 | -122.321065 | [1355135266](https://www.openstreetmap.org/way/1355135266) | River Road | primary | 29.6 | no |
| Riverwalk Trail | - | segment mid t=0.75 | 47.206685 | -122.320589 | [1355135266](https://www.openstreetmap.org/way/1355135266) | River Road | primary | 24.1 | no |
| Riverwalk Trail | - | segment mid t=0.25 | 47.204883 | -122.314285 | [12192692](https://www.openstreetmap.org/way/12192692) | River Road | primary | 19.2 | no |
| Riverwalk Trail | - | segment mid t=0.25 | 47.202807 | -122.305154 | [1384462577](https://www.openstreetmap.org/way/1384462577) | - | service | 28.4 | no |
| Riverwalk Trail | - | segment mid t=0.75 | 47.202633 | -122.304021 | [1000417053](https://www.openstreetmap.org/way/1000417053) | - | path | 21.8 | no |
| Riverwalk Trail | - | segment mid t=0.25 | 47.202316 | -122.294769 | [249847275](https://www.openstreetmap.org/way/249847275) | - | service | 28.4 | no |
| Riverwalk Trail | - | segment mid t=0.25 | 47.201839 | -122.292503 | [670339893](https://www.openstreetmap.org/way/670339893) | - | cycleway | 27.0 | no |
| Riverwalk Trail | - | segment mid t=0.75 | 47.199364 | -122.288399 | [700893947](https://www.openstreetmap.org/way/700893947) | - | cycleway | 24.6 | no |
| Sumner Link Trail | - | segment mid t=0.75 | 47.199448 | -122.256193 | [12229434](https://www.openstreetmap.org/way/12229434) | 63rd Street East | residential | 27.7 | no |
| Sumner Link Trail | - | segment mid t=0.25 | 47.197483 | -122.250129 | [1043294945](https://www.openstreetmap.org/way/1043294945) | - | cycleway | 8.6 | no |
| Sumner Link Trail | - | segment mid t=0.25 | 47.200942 | -122.252849 | [12231860](https://www.openstreetmap.org/way/12231860) | State Street | residential | 23.8 | no |
| Sumner Link Trail | - | segment mid t=0.75 | 47.202217 | -122.249871 | [12235089](https://www.openstreetmap.org/way/12235089) | Spinning Avenue | residential | 28.0 | no |
| Sumner Link Trail | - | segment mid t=0.25 | 47.213863 | -122.240813 | [569775228](https://www.openstreetmap.org/way/569775228) | - | service | 29.4 | no |
| Sumner Link Trail | - | segment mid t=0.75 | 47.214087 | -122.240724 | [569775237](https://www.openstreetmap.org/way/569775237) | - | service | 29.0 | no |
| Sumner Link Trail | - | segment mid t=0.25 | 47.214308 | -122.241791 | [1005421281](https://www.openstreetmap.org/way/1005421281) | - | footway | 28.8 | no |
| Sumner Link Trail | - | segment mid t=0.75 | 47.214698 | -122.241565 | [956118281](https://www.openstreetmap.org/way/956118281) | 142nd Avenue East | tertiary | 28.3 | no |
| Sumner Link Trail | - | segment mid t=0.25 | 47.214893 | -122.241409 | [956118281](https://www.openstreetmap.org/way/956118281) | 142nd Avenue East | tertiary | 29.1 | no |
| Sumner Link Trail | - | segment mid t=0.25 | 47.215700 | -122.240515 | [569775239](https://www.openstreetmap.org/way/569775239) | - | service | 26.8 | no |
| Sumner Link Trail | - | segment mid t=0.75 | 47.216034 | -122.239871 | [569775238](https://www.openstreetmap.org/way/569775238) | - | service | 29.0 | no |
| Sumner Link Trail | - | segment mid t=0.75 | 47.217116 | -122.236456 | [569775219](https://www.openstreetmap.org/way/569775219) | - | service | 26.4 | no |
| Sumner Link Trail | - | segment mid t=0.25 | 47.217443 | -122.234256 | [569775219](https://www.openstreetmap.org/way/569775219) | - | service | 29.9 | no |
| Sumner Link Trail | - | segment mid t=0.75 | 47.217636 | -122.233578 | [1005421396](https://www.openstreetmap.org/way/1005421396) | - | footway | 21.5 | no |
| Sumner Link Trail | - | segment mid t=0.75 | 47.218897 | -122.230801 | [569775191](https://www.openstreetmap.org/way/569775191) | - | service | 24.8 | no |
| Sumner Link Trail | - | segment mid t=0.50 | 47.221605 | -122.232564 | [894511766](https://www.openstreetmap.org/way/894511766) | - | service | 13.8 | no |
| Sumner Link Trail | - | segment mid t=0.75 | 47.228389 | -122.230547 | [1297893046](https://www.openstreetmap.org/way/1297893046) | - | path | 26.4 | no |
| Sumner Link Trail | - | segment mid t=0.75 | 47.235231 | -122.233598 | [1297893046](https://www.openstreetmap.org/way/1297893046) | - | path | 17.5 | no |
| Sumner Link Trail | - | segment mid t=0.25 | 47.235537 | -122.235356 | [1297893053](https://www.openstreetmap.org/way/1297893053) | - | path | 21.1 | no |
| Sumner Link Trail | - | segment mid t=0.75 | 47.235561 | -122.237029 | [691257989](https://www.openstreetmap.org/way/691257989) | - | cycleway | 10.5 | no |
| Sumner Link Trail | - | segment mid t=0.25 | 47.235720 | -122.236896 | [691257989](https://www.openstreetmap.org/way/691257989) | - | cycleway | 27.1 | no |
| Sumner Link Trail | - | segment mid t=0.75 | 47.242625 | -122.235424 | [261467894](https://www.openstreetmap.org/way/261467894) | - | cycleway | 22.2 | no |
| Sumner Link Trail | - | segment mid t=0.25 | 47.243054 | -122.235606 | [261467894](https://www.openstreetmap.org/way/261467894) | - | cycleway | 28.1 | no |
| Sumner Link Trail | - | segment mid t=0.75 | 47.243881 | -122.235794 | [1297893061](https://www.openstreetmap.org/way/1297893061) | - | path | 16.9 | no |
| Sumner Link Trail | - | segment mid t=0.25 | 47.244327 | -122.235643 | [1297893061](https://www.openstreetmap.org/way/1297893061) | - | path | 27.6 | no |
| Sumner Link Trail | - | segment mid t=0.75 | 47.244667 | -122.234996 | [1173169492](https://www.openstreetmap.org/way/1173169492) | - | path | 9.5 | no |
| Sumner Link Trail | - | segment mid t=0.25 | 47.245221 | -122.235146 | [1173169492](https://www.openstreetmap.org/way/1173169492) | - | path | 29.8 | no |
| Sumner Link Trail | - | segment mid t=0.75 | 47.245888 | -122.236659 | [1173169493](https://www.openstreetmap.org/way/1173169493) | - | path | 21.7 | no |
| Sumner Link Trail | - | segment mid t=0.25 | 47.245959 | -122.237318 | [1173169493](https://www.openstreetmap.org/way/1173169493) | - | path | 24.9 | no |
| Lake Tapps Parkway Trail | - | segment mid t=0.75 | 47.251760 | -122.229164 | [1326182522](https://www.openstreetmap.org/way/1326182522) | East Valley Highway East | secondary | 27.1 | no |

### Trail endpoints and internal chain gaps

| trail | chain | length (m) | nodes | start lat | start lon | end lat | end lon |
|---|---|---|---|---|---|---|---|
| White River Trail | 0 | 3878.9 | 221 | 47.264747 | -122.229213 | 47.282698 | -122.193362 |
| Interurban Trail Milton | 0 | 3986.6 | 92 | 47.261063 | -122.300768 | 47.243335 | -122.334533 |
| Interurban Trail Milton | 1 | 328.5 | 26 | 47.239329 | -122.334345 | 47.240728 | -122.336437 |
| Interurban Trail Edgewood | 0 | 1330.2 | 35 | 47.258350 | -122.293487 | 47.256065 | -122.277330 |
| Riverwalk Trail | 0 | 2956.4 | 82 | 47.207103 | -122.323052 | 47.198570 | -122.288267 |
| Riverwalk Trail | 1 | 3713.9 | 97 | 47.197791 | -122.287804 | 47.196487 | -122.251072 |
| Sumner Link Trail | 0 | 6742.7 | 261 | 47.203308 | -122.244243 | 47.250099 | -122.237503 |
| Sumner Link Trail | 1 | 980.4 | 35 | 47.200730 | -122.252799 | 47.196487 | -122.251072 |
| Sumner Link Trail | 2 | 794.8 | 35 | 47.200739 | -122.253084 | 47.203456 | -122.244115 |
| Sumner Link Trail | 3 | 1031.3 | 63 | 47.212030 | -122.242087 | 47.216519 | -122.238428 |
| Sumner Link Trail | 4 | 70.2 | 6 | 47.203325 | -122.244336 | 47.203904 | -122.244034 |
| Lake Tapps Parkway Trail | 0 | 3016.1 | 98 | 47.245316 | -122.209513 | 47.249991 | -122.241576 |

Gaps between chain ends (a multi-chain trail is fragmented *inside* the relation):

| trail | chain A | chain B | min end-to-end gap (m) |
|---|---|---|---|
| Interurban Trail Milton | 0 | 1 | 323.5 |
| Riverwalk Trail | 0 | 1 | 93.4 |
| Sumner Link Trail | 0 | 4 | 7.3 |
| Sumner Link Trail | 0 | 2 | 19.1 |
| Sumner Link Trail | 1 | 2 | 21.6 |
| Sumner Link Trail | 2 | 4 | 22.2 |
| Sumner Link Trail | 1 | 4 | 701.4 |
| Sumner Link Trail | 0 | 1 | 707.1 |
| Sumner Link Trail | 3 | 4 | 915.5 |
| Sumner Link Trail | 2 | 3 | 965.6 |
| Sumner Link Trail | 0 | 3 | 983.4 |
| Sumner Link Trail | 1 | 3 | 1494.5 |

### Known case: way `1297893064` (informal path) vs way `569772368` (service road)

* `1297893064` tags: `{"highway": "path", "informal": "yes"}`, 14 nodes, ends at (47.224641, -122.232535) and (47.222625, -122.233677).
* `569772368` tags: `{"highway": "service"}`, 11 nodes.
* Shared node count between the two ways: **0**. Minimum geometric separation: **39.77 m**.
* Shared nodes with Sumner Link Trail relation 9605518: **[12024618771]** (member way [[419747831]]).
* Minimum distance from the path to Sumner Link Trail: 0.0 m.
* CONFIRMED: way 1297893064 shares 0 nodes with service road 569772368. It DOES share node [12024618771] with member way [[419747831]] of Sumner Link Trail relation 9605518, so the path is a dead-end spur hanging off the trail.

Roads within 60 m of the spur (nearest to the spur's free end first):

| road way | name | highway | surface | dist to path (m) | dist to free end (m) | shares node with path |
|---|---|---|---|---|---|---|
| [1297893047](https://www.openstreetmap.org/way/1297893047) | - | path | unpaved | 0.0 | 0.0 | YES |
| [419747831](https://www.openstreetmap.org/way/419747831) | Sumner Link Trail | unclassified | - | 0.0 | 12.0 | YES |
| [569772368](https://www.openstreetmap.org/way/569772368) | - | service | - | 39.8 | 39.8 | no |

### White River Trail: two-hop reachability from its link ways

The White River Trail shares nodes only with path-like ways (`footway`, `cycleway`, `track`, `path`, one `service`) - no drivable road shares a node with it directly. The table below asks whether those link ways themselves touch a drivable road.

* link ways of the White River Trail (non-member highway ways sharing a node with it): **24**
* link ways that share a node with at least one drivable road class: **7**

| link way | highway | name | shares node with drivable road class |
|---|---|---|---|
| [354380326](https://www.openstreetmap.org/way/354380326) | track | - | residential |
| [437531322](https://www.openstreetmap.org/way/437531322) | footway | - | **none** |
| [437531327](https://www.openstreetmap.org/way/437531327) | footway | - | **none** |
| [461442379](https://www.openstreetmap.org/way/461442379) | footway | - | **none** |
| [674021791](https://www.openstreetmap.org/way/674021791) | cycleway | - | tertiary |
| [674021792](https://www.openstreetmap.org/way/674021792) | cycleway | - | tertiary |
| [674202154](https://www.openstreetmap.org/way/674202154) | service | - | residential, secondary, service |
| [845567702](https://www.openstreetmap.org/way/845567702) | cycleway | - | **none** |
| [845567703](https://www.openstreetmap.org/way/845567703) | cycleway | - | tertiary |
| [845567705](https://www.openstreetmap.org/way/845567705) | cycleway | - | **none** |
| [845567706](https://www.openstreetmap.org/way/845567706) | cycleway | - | **none** |
| [845567707](https://www.openstreetmap.org/way/845567707) | footway | - | **none** |
| [947658197](https://www.openstreetmap.org/way/947658197) | footway | - | secondary |
| [947658199](https://www.openstreetmap.org/way/947658199) | footway | - | **none** |
| [947658200](https://www.openstreetmap.org/way/947658200) | footway | - | **none** |
| [947658850](https://www.openstreetmap.org/way/947658850) | cycleway | - | **none** |
| [1149786986](https://www.openstreetmap.org/way/1149786986) | footway | - | **none** |
| [1149786987](https://www.openstreetmap.org/way/1149786987) | footway | - | **none** |
| [1149786989](https://www.openstreetmap.org/way/1149786989) | path | - | **none** |
| [1349703114](https://www.openstreetmap.org/way/1349703114) | footway | - | **none** |
| [1392256034](https://www.openstreetmap.org/way/1392256034) | footway | - | **none** |
| [1392256035](https://www.openstreetmap.org/way/1392256035) | footway | - | **none** |
| [1392256036](https://www.openstreetmap.org/way/1392256036) | footway | - | service |
| [1528680020](https://www.openstreetmap.org/way/1528680020) | cycleway | - | **none** |

## KNOWN LIMITS

* **Proximity is sampled at trail nodes plus segment interiors.** The 30 m test is
  point-to-polyline: every trail node, plus samples at t = 0.25/0.50/0.75 of every trail segment. Roads
  whose closest approach falls between two sample points are not listed, so a handful of very short
  (<5 m) near-miss stretches may be missing. The `proximity_segment_midpoints` array in `islands.json`
  lists the sub-30 m approaches that node sampling alone would have missed.
* **"Shares a node" is the routable case; "within 30 m" is not.** A trail node that is also a node of a
  non-member `highway=*` way is a real OSM graph junction. A trail node merely within 30 m of a road
  without a shared node is *not* an OSM junction: OSM topology does not connect them. Routers that snap
  by nearest edge (Valhalla, OSRM, GraphHopper) may still connect them, but that is a snapper artifact,
  not an OSM connection.
* **`highway=*` is a broad class.** The connectivity count includes `footway`, `path`, `cycleway`,
  `track` and `service`, not only drivable roads. `shared_road_highway_breakdown` and
  `shared_nodes_with_road_class` separate these: `shared_nodes_with_road_class` counts shared nodes with
  a *drivable* class (motorway/trunk/primary/secondary/tertiary/unclassified/residential/living_street/
  service/road). A trail whose shared nodes are all path-like still connects to the network only through
  those paths.
* **Relation membership defines the trail.** A way geometrically identical to a trail but absent from the
  relation counts as a separate road way. Conversely the relation may be a *fragment* of the named trail.
* **Chain assembly.** Member ways are adjacent when they share any node id. If the members do not form a
  single chain, the trail is reported with several chains plus the gap between chain ends: a multi-chain
  trail is topologically fragmented inside the relation itself.
* **Overpass instances / time.** The per-trail response records which instance answered and the OSM
  snapshot (`timestamp_osm_base`). overpass-api.de rate-limited (HTTP 429) and overpass.kumi.systems
  answered some queries; mirror snapshots can be older than the main instance snapshot.
* **What this does NOT test.** No router was run and no costing was applied. OSM topology alone does not
  say whether a router may use the edge (`access=no`, `bicycle=no`), whether the surface is rideable, or
  whether the trail is in the routing graph at all.

