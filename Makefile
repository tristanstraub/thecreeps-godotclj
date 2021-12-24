all: assets/dodge_assets assets/icon.png lib/godot-3.4.1.jar

assets:
	mkdir -p assets

assets/dodge_assets.zip: assets
	curl https://docs.godotengine.org/en/stable/_downloads/e79a087a28c8eb4d140359198a122c0f/dodge_assets.zip -o assets/dodge_assets.zip

assets/dodge_assets: assets/dodge_assets.zip
	unzip -u assets/dodge_assets.zip -d assets

assets/icon.png: assets
	curl https://godotengine.org/themes/godotengine/assets/press/icon_color.png -o assets/icon.png

lib/godot-3.4.1.jar:
	mkdir -p $(shell dirname $@)
	curl https://github.com/tristanstraub/godotclj/releases/godot-3.4.1.jar -o $@
