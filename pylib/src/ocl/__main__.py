"""Enable ``python -m ocl`` to launch the CLI controller."""

import sys

from .cli import main

if __name__ == "__main__":
    sys.exit(main())
