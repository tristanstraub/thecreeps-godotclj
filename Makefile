GODOTCLJ_VERSION=v0.0.5
JAR_URL=https://github.com/tristanstraub/godotclj/releases/download/$(GODOTCLJ_VERSION)/godotclj.jar

all: assets/dodge_assets assets/icon.png lib/godotclj.jar

clean:
	rm -fr lib natives cache

assets:
	mkdir -p assets

assets/dodge_assets.zip: assets
	curl https://docs.godotengine.org/en/stable/_downloads/e79a087a28c8eb4d140359198a122c0f/dodge_assets.zip -o assets/dodge_assets.zip

assets/dodge_assets: assets/dodge_assets.zip
	unzip -u assets/dodge_assets.zip -d assets

assets/icon.png: assets
	curl https://godotengine.org/themes/godotengine/assets/press/icon_color.png -o assets/icon.png

lib/godotclj.jar:
	mkdir -p $(shell dirname $@)
	curl -L $(JAR_URL) -o $@
