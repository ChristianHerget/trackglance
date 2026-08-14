# Bridge protocol v1

The Android bridge and Pebble watchapp exchange Pebble AppMessage dictionaries using UUID
`51c8d7cf-4cb2-4ef8-98c9-641706feb250`. Values use integer SI units so watch rendering is
independent of Android locale and floating-point transport.

| Key | Name | Value |
|---:|---|---|
| 0 | protocol version | `1` |
| 1 | message type | snapshot `1`, command `2`, command result `3`, request snapshot `4` |
| 2 | command ID | monotonically increasing unsigned integer |
| 3 | command | start `1`, pause/resume `2`, stop/save `3`, waypoint `4` |
| 4 | result | OK `0`, invalid state `1`, Locus unavailable `2`, failed `3` |
| 5 | recording state | stopped `0`, recording `1`, paused `2`, unavailable `3` |
| 6 | sample time | Unix seconds |
| 10 | elapsed time | seconds |
| 11 | distance | metres |
| 12 | current speed | centimetres/second |
| 13 | average speed | centimetres/second |
| 14 | altitude | decimetres, signed |
| 15 | ascent | decimetres |
| 16 | units | metric `0`, imperial `1` (reserved; v1 emits metric) |

New message types and keys may be added without changing existing meanings. Receivers reject a
different protocol version, ignore unknown keys, and retain the last complete snapshot.

