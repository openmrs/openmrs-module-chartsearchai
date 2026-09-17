# shellcheck shell=sh
#
# Fetch a model file and refuse it unless it is the artifact model-manifest.tsv records.
#
# Issues #444 and #449 are one defect with two consumers, and this library is what both now call,
# so the pinned revision and the expected digest exist once rather than once per consumer. ADR
# Decision 103 has the reasoning and names the consumers; the Callers list below is the current
# roster.
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
#   3  the fetch or the placement failed, so nothing was verified and nothing was deleted
#      (a non-2xx response is here, because curl runs with -f)
#   4  the artifact could not be resolved: no such id in the manifest, or an override with no digest
#   5  the file could not be measured or hashed at all, and is still on disk
#
# 1 and 2 are the only codes that promise a deletion, and the callers' wording leans on that.

# _mm_field <id> <sha256|bytes|url> — one field of the manifest row named by <id>, or a failure
# naming the id.
#
# Read with the shell's own `read` rather than awk or cut, which the entrypoint does not already
# use: a lookup over a handful of rows is not worth adding a tool to what the container has to
# carry. Nothing here needs what awk would give.
_mm_field() {
	if [ ! -f "$MODEL_MANIFEST_FILE" ]; then
		echo "ERROR: no manifest at $MODEL_MANIFEST_FILE." >&2
		return 1
	fi
	_mm_f_value=''
	# `=` is an exact comparison on purpose: an id that is a prefix of another would otherwise hand
	# back a neighbour's digest, which every later check would then pass. No committed pair is like
	# that today, so the rule is pinned by a fixture rather than by the manifest — see
	# ModelDownloadIntegrityTest.anIdThatIsAPrefixOfAnotherResolvesToItsOwnRowInEitherOrder.
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
		echo "ERROR: no artifact '$1' in $MODEL_MANIFEST_FILE." >&2
		return 1
	fi
	printf '%s\n' "$_mm_f_value"
}

manifest_sha256() { _mm_field "$1" sha256; }

manifest_bytes() { _mm_field "$1" bytes; }

manifest_url() { _mm_field "$1" url; }

# file_sha256 <file> — the file's sha256 as lowercase hex. Three tools are tried because the fetch
# sites do not share an environment: the backend image is Debian and has coreutils `sha256sum`, a
# maintainer's macOS checkout may have only `shasum`, and `openssl` is common on both. The same
# hedge the size guards this replaced used to make between GNU and BSD `stat`, for the same reason.
#
# `shasum` is tried LAST rather than second because it is a Perl implementation and measurably
# slower — ADR Decision 103 records the rates, and the gap is wide enough to matter over multi-GB
# weights. Its digest agrees with the other two; only its speed differs.
file_sha256() {
	if command -v sha256sum >/dev/null 2>&1; then
		_mm_sum=$(sha256sum "$1") || return 1
		printf '%s\n' "${_mm_sum%% *}"
	elif command -v openssl >/dev/null 2>&1; then
		_mm_sum=$(openssl dgst -sha256 "$1") || return 1
		printf '%s\n' "${_mm_sum##*= }"
	elif command -v shasum >/dev/null 2>&1; then
		_mm_sum=$(shasum -a 256 "$1") || return 1
		printf '%s\n' "${_mm_sum%% *}"
	else
		echo "ERROR: no sha256 tool available (looked for sha256sum, openssl, shasum)." >&2
		return 1
	fi
}

# file_bytes <file> — GNU stat, then BSD stat. Fails rather than answering 0, because 0 is a
# measurement and "I have no stat" is not: answering it deleted correct files and re-fetched them
# forever, reporting them as 0 bytes.
file_bytes() {
	stat -c %s "$1" 2>/dev/null || stat -f %z "$1" 2>/dev/null
}

# _mm_verify_file <file> <sha256> <bytes> <label> <source> — size first, then digest, deleting the file on
# either failure so no later start resumes it or loads it. Size is checked first only so the caller
# can say something specific about a short file; the digest is what actually binds the bytes.
# <bytes> of 0 means the size is not known ahead of time, which is the manually-dispatched build.
_mm_verify_file() {
	if ! _mm_vf_size=$(file_bytes "$1"); then
		echo "ERROR: $4 could not be measured, so it has not been verified either way." >&2
		return 5
	fi
	if [ "$3" -gt 0 ] && [ "$_mm_vf_size" -ne "$3" ]; then
		echo "ERROR: $4 did not deliver the recorded length: ${_mm_vf_size} bytes, not $3." >&2
		echo "       Refusing it and deleting $1." >&2
		rm -f "$1"
		return 2
	fi

	# 5, not 1: nothing has been deleted, and the callers' "refused and deleted" wording is keyed
	# on the code rather than on re-checking the disk.
	_mm_vf_actual=$(file_sha256 "$1") || return 5
	if [ "$_mm_vf_actual" != "$2" ]; then
		echo "ERROR: $4 is not the artifact $5 records." >&2
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
# The composed step the two wrappers below delegate to, and through them what both fetch sites
# reach: a file is at <target> when this returns 0, and it is the reviewed artifact. Everything
# else returns a code from the table above.
#
# A file already at <target> is verified rather than trusted for its name — see the fall-through
# below, and ADR Decision 103 for why.
fetch_and_verify_url() {
	_mm_url=$1
	_mm_expected=$2
	_mm_bytes=$3
	_mm_target=$4
	_mm_label=$5
	# Where the expected digest came from, named in the refusal. The manifest for everything a
	# push build or the entrypoint fetches; a workflow input for a dispatched override, where
	# naming the manifest would send an operator to a file it deliberately does not record.
	_mm_source=${6:-model-manifest.tsv}
	_mm_partial="$_mm_target.partial"

	# The verification is the condition of an `if` rather than a bare call, because the standalone
	# workflow runs this under `set -e`: a bare call that failed would end that shell on the spot,
	# and neither the fall-through below nor any exit code would ever be reached.
	if [ -f "$_mm_target" ]; then
		if _mm_verify_file "$_mm_target" "$_mm_expected" "$_mm_bytes" "$_mm_label" "$_mm_source"; then
			return 0
		else
			# $? has to be read inside the else: after a bare `if ... fi` whose condition failed
			# it is 0, not the condition's status.
			_mm_status=$?
			# Only 1 and 2 deleted the file, and only a deleted file may be replaced. Any other
			# code means the verdict is unknown and the file is still there — falling through
			# would claim a deletion that did not happen and go on serving unverified bytes.
			if [ "$_mm_status" -ne 1 ] && [ "$_mm_status" -ne 2 ]; then
				return "$_mm_status"
			fi
		fi
		# Fall through and fetch what the manifest records — ADR Decision 103 for why replacing
		# beats refusing here. A served copy that fails too is the refusal.
		echo "Replacing $_mm_label from the revision $_mm_source records..."
	fi

	if [ -f "$_mm_partial" ]; then
		echo "Resuming $_mm_label download..."
		_mm_resumed=yes
	else
		echo "Downloading $_mm_label..."
		_mm_resumed=no
	fi
	# -f so a non-2xx response is a failure rather than an HTML error page renamed into place;
	# -C - to resume a .partial across a container restart; --speed-time/--speed-limit to abort a
	# connection Hugging Face has stalled without closing, rather than hanging the container start.
	if ! curl -fsSL -C - --speed-time 60 --speed-limit 1024 -o "$_mm_partial" "$_mm_url"; then
		echo "ERROR: $_mm_label could not be downloaded from $_mm_url." >&2
		if [ "$_mm_resumed" = yes ]; then
			# A failed resume can fail forever, and nothing else deletes the partial: curl exits
			# 33 when the origin answers a Range request with a whole 200, which a caching proxy
			# in front of the container will do. Start from zero next time instead of retrying a
			# request that cannot succeed.
			echo "       Discarding the partial download so the next attempt starts from zero." >&2
			rm -f "$_mm_partial"
		fi
		return 3
	fi

	_mm_verify_file "$_mm_partial" "$_mm_expected" "$_mm_bytes" "$_mm_label" "$_mm_source" || return $?

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
	fetch_and_verify_url "$1" "$2" 0 "$3" "$4" "the $5 input"
}

# fetch_or_exit <manifest-id> <target> <label> [diagnostic-line...]
#
# For an artifact the module cannot start without — the querystore embedder and its vocab, whose
# paths configure_retrieval_gps writes into global properties seconds later. Fetches and verifies as
# fetch_and_verify does, and on ANY refusal prints the caller's diagnostic lines, says what the code
# means, and EXITS rather than returning.
#
# Exiting here rather than leaving the caller to branch is the point. A caller that branched had to
# spell the branch correctly, and every spelling of it turned out to be a way to get it wrong: a
# statement between the fetch and the branch made `$?` that statement's status; an arm that printed
# the word "exit" without running it; a glob arm nothing recognised; a pattern list `0|2)` that
# folded the refusal into the success case. All four shipped past a source-reading guard that had
# been repaired for the previous one, and each repair is what made the next reachable. There is no
# branch to spell now, and the property is a behaviour a
# test can drive — ModelDownloadIntegrityTest.aRefusalOfAnArtifactTheModuleCannotStartWithoutStopsTheScript.
fetch_or_exit() {
	_mm_oe_id=$1
	_mm_oe_target=$2
	_mm_oe_label=$3
	shift 3
	if fetch_and_verify "$_mm_oe_id" "$_mm_oe_target" "$_mm_oe_label"; then
		return 0
	else
		_mm_oe_code=$?
	fi

	case $_mm_oe_code in
		2)
			for _mm_oe_line in "$@"; do
				echo "       $_mm_oe_line" >&2
			done
			;;
		4)
			# Code 4 is any failure to RESOLVE the artifact — a missing row, and also a manifest
			# that is not there at all, which the line above this one will have said. Both mean
			# the image is built wrong rather than that a fetch went badly.
			echo "       The artifact could not be resolved from $MODEL_MANIFEST_FILE, so the image" >&2
			echo "       is built wrong and a restart will not help." >&2
			;;
		*)
			echo "       Chart search cannot run without a verified copy of this file, so the start" >&2
			echo "       is refused rather than left to fail at the first query." >&2
			;;
	esac
	exit "$_mm_oe_code"
}

# require_url_for_digest <url> <sha256> <url-input-name> <digest-input-name>
#
# The mirror of fetch_and_verify_override's refusal: a digest with no url of its own would be
# accepted and then ignored, and the build would quietly bundle the manifest's model instead of the
# one that was asked for. Here rather than in the workflow because the input names have to be
# spelled rather than composed — `vocab_url` is not `vocab_model_url`, and an inline version got
# that wrong with nothing able to notice.
require_url_for_digest() {
	if [ -z "$1" ] && [ -n "$2" ]; then
		echo "ERROR: $4 was given without $3, so it would check nothing." >&2
		return 4
	fi
	return 0
}

# fetch_and_verify <manifest-id> <target> <label> — the same step, with the url, digest and size
# taken from the manifest. This is the form the entrypoint uses for all four of its artifacts and
# the standalone build uses for everything a push build fetches.
fetch_and_verify() {
	_mm_fv_url=$(manifest_url "$1") || return 4
	_mm_fv_sha=$(manifest_sha256 "$1") || return 4
	_mm_fv_bytes=$(manifest_bytes "$1") || return 4
	fetch_and_verify_url "$_mm_fv_url" "$_mm_fv_sha" "$_mm_fv_bytes" "$2" "$3"
}
