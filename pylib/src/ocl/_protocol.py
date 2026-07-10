"""Shared OCLO/OCLI/OCLV wire constants (mirror of the mod's Protocol.java).

GENERATED FROM protocol/protocol.json BY protocol/gen_constants.py — DO NOT EDIT BY HAND.
"""

VERSION = 2
VIS_VERSION = 1

# Movement bitmask (1 << position).
M_FRONT = 1 << 0
M_BACK = 1 << 1
M_LEFT = 1 << 2
M_RIGHT = 1 << 3
M_JUMP = 1 << 4
M_SPRINT = 1 << 5
M_SNEAK = 1 << 6

# Action bitmask (1 << position).
A_ATTACK = 1 << 0
A_INTERACT = 1 << 1

# Slot-group wire opcodes.
G_HOTBAR = 0
G_OFFHAND = 1
G_ARMOR = 2
G_INVENTORY = 3
G_CURSOR = 4
G_DISCARD = 5
G_EXTENSION = 6

# Inventory-action wire opcodes.
OP_NONE = 0
OP_MOVE = 1
OP_PICK = 2
OP_PUT = 3
OP_SWAP = 4
OP_DROP = 5
OP_DISTRIBUTE = 6
OP_COLLECT = 7
