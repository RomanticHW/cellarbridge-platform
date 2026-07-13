SHELL := /bin/bash
PYTHON ?= python3

.PHONY: help validate validate-docs validate-contracts validate-public

help:
	@printf '%s\n' \
	  'CellarBridge design-baseline commands:' \
	  '  make validate            Run all repository checks' \
	  '  make validate-docs       Validate Markdown structure and relative links' \
	  '  make validate-contracts  Parse YAML/JSON and validate event examples' \
	  '  make validate-public     Check public/private boundary and repository hygiene'

validate: validate-docs validate-contracts validate-public

validate-docs:
	$(PYTHON) scripts/validate_repository.py --scope docs

validate-contracts:
	$(PYTHON) scripts/validate_repository.py --scope contracts

validate-public:
	$(PYTHON) scripts/validate_repository.py --scope public
