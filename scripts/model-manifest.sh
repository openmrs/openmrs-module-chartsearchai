# shellcheck shell=sh
#
# Fetch a model file and refuse it unless it is the artifact model-manifest.tsv records.
#
# Issues #444 and #449 are one defect with two consumers: backend-init.sh provisions the models a
# deployed container executes, and .github/workflows/build-standalone.yml bakes them into the
# download the README advertises. Both fetched from a mutable third-party branch and bound the
# received bytes to nothing, so whoever controlled those repositories at fetch time decided what the
# clinical LLM says about every patient chart. This library is what both now call instead, so the
# pinned revision and the expected digest exist once, in one file, rather than once per consumer.
# The reasoning is ADR Decision 103.
#
# POSIX sh, because backend-init.sh is `#!/bin/sh` and runs under dash in the backend image. In
# particular: no `local`, so every variable here is prefixed `_mm_` to stay clear of the sourcing
# script's own names — backend-init.sh reuses `_url` for a JDBC value and runs two of these fetches
# in parallel background subshells.
#
# Sourced, never executed. Callers:
#   backend-init.sh ............... . /usr/local/bin/model-manifest.sh
#   build-standalone.yml .......... . chartsearchai-backend/scripts/model-manifest.sh
#   ModelDownloadIntegrityTest .... drives it with /bin/sh against a loopback HTTP server

# Where the digests live. The default is the path Dockerfile.backend COPYs the manifest to; the
# workflow and the test both point this at their own checkout.
MODEL_MANIFEST_FILE="${MODEL_MANIFEST_FILE:-/usr/local/share/chartsearchai/model-manifest.tsv}"

# Exit codes fetch_and_verify_url contracts with its callers, which branch on them to say something
# useful about the artifact they asked for:
#   0  the file is present and is the reviewed artifact
#   1  digest mismatch — the file has been deleted
#   2  size mismatch — the file has been deleted, and the caller has a better diagnostic than we do
#   3  the download itself failed (including any non-2xx response, because curl runs with -f)
#   4  the manifest does not carry the requested id

# _mm_field <id> <sha256|bytes|url> — one field of the manifest row named by <id>, or a failure
# naming the id.
#
# Read with the shell's own `read` rather than awk or cut. backend-init.sh depends on curl, sed,
# grep and stat and on no text processor beyond them, and a five-row lookup is not worth making the
# container entrypoint's tool list longer than it was.
_mm_field() {
	if [ ! -f "$MODEL_MANIFEST_FILE" ]; then
		echo "model-manifest: no manifest at $MODEL_MANIFEST_FILE" >&2
		return 1
	fi
	_mm_f_value=''
	# `=` is an exact comparison on purpose: one id here is a prefix of another, and a pattern
	# match would hand back a neighbour's digest, which every later check would then pass.
	while read -r _mm_f_id _mm_f_sha _mm_f_bytes _mm_f_url _mm_f_rest || [ -n "$_mm_f_id" ]; do
		case "$_mm_f_id" in '' | \#*) continue ;; esac
		[ "$_mm_f_id" = "$1" ] || continue
		case "$2" in
			sha256) _mm_f_value=$_mm_f_sha ;;
			bytes) _mm_f_value=$_mm_f_bytes ;;
			url) _mm_f_value=$_mm_f_url ;;
			*)
				echo "model-manifest: '$2' is not a manifest field" >&2
				return 1
				;;
		esac
		break
	done < "$MODEL_MANIFEST_FILE"

	if [ -z "$_mm_f_value" ]; then
		echo "model-manifest: no artifact '$1' in $MODEL_MANIFEST_FILE" >&2
		return 1
	fi
	printf '%s\n' "$_mm_f_value"
}

manifest_sha256() { _mm_field "$1" sha256; }

manifest_bytes() { _mm_field "$1" bytes; }

manifest_url() { _mm_field "$1" url; }

# file_sha256 <file> — the file's sha256 as lowercase hex. The three tools are tried in turn
# because the fetch sites do not share an environment: the backend image is Debian (coreutils
# sha256sum), a maintainer's macOS checkout has shasum, and openssl is the last resort. The same
# hedge the neighbouring stat calls in backend-init.sh make, for the same reason.
file_sha256() {
	if command -v sha256sum >/dev/null 2>&1; then
		_mm_sum=$(sha256sum "$1") || return 1
		printf '%s\n' "${_mm_sum%% *}"
	elif command -v shasum >/dev/null 2>&1; then
		_mm_sum=$(shasum -a 256 "$1") || return 1
		printf '%s\n' "${_mm_sum%% *}"
	elif command -v openssl >/dev/null 2>&1; then
		_mm_sum=$(openssl dgst -sha256 "$1") || return 1
		printf '%s\n' "${_mm_sum##*= }"
	else
		echo "model-manifest: no sha256 tool available (looked for sha256sum, shasum, openssl)." >&2
		return 1
	fi
}

# file_bytes <file> — GNU stat, then BSD stat, then 0 for a file that is not there.
file_bytes() {
	stat -c %s "$1" 2>/dev/null || stat -f %z "$1" 2>/dev/null || echo 0
}

# _mm_verify_file <file> <sha256> <bytes> <label> — size first, then digest, deleting the file on
# either failure so no later start resumes it or loads it. Size is checked first only so the caller
# can say something specific about a short file; the digest is what actually binds the bytes.
# <bytes> of 0 means the size is not known ahead of time, which is the manually-dispatched build.
_mm_verify_file() {
	_mm_vf_size=$(file_bytes "$1")
	if [ "$3" -gt 0 ] && [ "$_mm_vf_size" -ne "$3" ]; then
		echo "ERROR: $4 is ${_mm_vf_size} bytes; the reviewed artifact is $3 bytes." >&2
		echo "       Refusing it and deleting $1." >&2
		rm -f "$1"
		return 2
	fi

	_mm_vf_actual=$(file_sha256 "$1") || return 1
	if [ "$_mm_vf_actual" != "$2" ]; then
		echo "ERROR: $4 is not the artifact model-manifest.tsv records." >&2
		echo "       expected sha256 $2" >&2
		echo "       received sha256 $_mm_vf_actual" >&2
		echo "       Refusing it and deleting $1." >&2
		rm -f "$1"
		return 1
	fi
	return 0
}

# fetch_and_verify_url <url> <sha256> <bytes> <target> <label>
#
# The composed step both fetch sites call: a file is at <target> when this returns 0, and it is the
# reviewed artifact. Everything else deletes what it rejected and returns a code above.
#
# A file already at <target> is verified rather than trusted for its name. That is the case a fresh
# download cannot reach and the one #444 is largely about: /openmrs/data outlives the container, so
# a deployment provisioned before this check existed is carrying whatever it was served then.
fetch_and_verify_url() {
	_mm_url=$1
	_mm_expected=$2
	_mm_bytes=$3
	_mm_target=$4
	_mm_label=$5
	_mm_partial="$_mm_target.partial"

	# Written as a condition rather than as a bare call followed by `return $?` so the status is
	# propagated the same way under `set -e`, which the standalone workflow runs this under: a bare
	# call that failed would kill that shell before the code could be returned or branched on.
	if [ -f "$_mm_target" ]; then
		if _mm_verify_file "$_mm_target" "$_mm_expected" "$_mm_bytes" "$_mm_label"; then
			return 0
		fi
		# _mm_verify_file has deleted it, and we fall through to fetch the artifact the manifest
		# records. A file left by an older revision and a substituted one are indistinguishable
		# here, and refusing outright would cost a deployment one failed start before the next one
		# downloaded the right bytes anyway — the replacement is bound to the same digest, so this
		# changes how many restarts it takes and nothing about what is accepted. A served copy
		# that fails too is the refusal.
		echo "Replacing $_mm_label from the revision model-manifest.tsv records..."
	fi

	if [ -f "$_mm_partial" ]; then
		echo "Resuming $_mm_label download..."
	else
		echo "Downloading $_mm_label..."
	fi
	# -f so a non-2xx response is a failure rather than an HTML error page renamed into place;
	# -C - to resume a .partial across a container restart; --speed-time/--speed-limit to abort a
	# connection Hugging Face has stalled without closing, rather than hanging the container start.
	if ! curl -fsSL -C - --speed-time 60 --speed-limit 1024 -o "$_mm_partial" "$_mm_url"; then
		echo "ERROR: $_mm_label could not be downloaded from $_mm_url." >&2
		return 3
	fi

	_mm_verify_file "$_mm_partial" "$_mm_expected" "$_mm_bytes" "$_mm_label" || return $?

	mv "$_mm_partial" "$_mm_target" || return 3
	return 0
}

# fetch_and_verify_override <url> <sha256> <target> <label> <digest-input-name>
#
# The manually-dispatched standalone build, where an operator names a file the manifest does not
# record — a larger Gemma, say. They must bring that file's digest with it: without one there is
# nothing to check the bytes against, which is the whole of what #449 reports, so this refuses
# rather than falling back to fetching it unverified. The size is unknown, hence 0.
#
# Here rather than inline in the workflow because this is the one branch in that step with a
# decision in it, and shell embedded in a YAML `run:` block is parsed by nothing in CI and
# reachable by no test.
fetch_and_verify_override() {
	if [ -z "$2" ]; then
		echo "ERROR: $4 was requested from $1, but no $5 was given." >&2
		echo "       A model that goes into the bundle needs a digest to check it against." >&2
		return 4
	fi
	fetch_and_verify_url "$1" "$2" 0 "$3" "$4"
}

# fetch_and_verify <manifest-id> <target> <label> — the same step, with the url, digest and size
# taken from the manifest. This is the form both fetch sites use; the url form above is for the
# manually-dispatched standalone build, where the operator supplies a url and its digest.
fetch_and_verify() {
	_mm_fv_url=$(manifest_url "$1") || return 4
	_mm_fv_sha=$(manifest_sha256 "$1") || return 4
	_mm_fv_bytes=$(manifest_bytes "$1") || return 4
	fetch_and_verify_url "$_mm_fv_url" "$_mm_fv_sha" "$_mm_fv_bytes" "$2" "$3"
}
