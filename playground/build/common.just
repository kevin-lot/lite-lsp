# Imported by ../justfile: its recipes and variables are shared.
root := justfile_directory()

clean:
    rm -rf {{root}}/web/dist
