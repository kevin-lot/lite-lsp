#!/usr/bin/env fish
# Ctrl+Click: `greet` -> functions/greet.fish, `$names` -> its `set`, `lib.fish` -> the file, `log` -> lib.fish.
source lib.fish

set names world fish
set names $names agents

for name in $names
    greet $name hello
    log "greeted $name"
end
