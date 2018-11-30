kernelDir = $(HOME)/.local/share/jupyter/kernels/iclojure

ifeq ($(shell uname -s), Linux)
        kernelDir:=$(HOME)/.local/share/jupyter/kernels/iclojure
endif

ifeq ($(shell uname -s), Darwin)
        kernelDir:=$(HOME)/Library/Jupyter/kernels/iclojure
endif

all:
	lein uberjar
	cat bin/iclj.template $$(find . -maxdepth 2 -type f | grep -e ".*standalone.*\.jar") > bin/iclj
	chmod +x bin/iclj

clean:
	rm -f *.jar
	rm -f target/*.jar
	rm -f bin/clojuypyter

install:
	mkdir -p $(kernelDir)
	cp bin/iclj $(kernelDir)/iclj
	sed 's|KERNEL|'${kernelDir}/iclj'|' resources/kernel.json > $(kernelDir)/kernel.json;\
