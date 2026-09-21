# shellcheck shell=sh
#
# Fetch a model file and refuse it unless it is the artifact model-manifest.tsv records.
#
# Issues #444 and #449 are one defect with two consumers, and this library is what both now call,
# so the pinned revision and the expected digest exist once rather than once per consumer. ADR
# Decision 106 has the reasoning and names the consumers; the Callers list below is the current
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

# The artifact ids that have VERIFIED in THIS shell, space-delimited and space-bounded on both
# sides so one id cannot match inside another. require_verified is the only reader; fetch_and_verify
# the only writer. No committed pair is a substring pair, so — as with _mm_field's exact comparison
# — the rule is pinned by a fixture rather than by the manifest: ModelDownloadIntegrityTest
# .anIdThatIsASubstringOfAnotherIsNotPublishableOnItsNeighboursVerification. Dropping the bounds
# fails OPEN, which is the other way round from the lookup's.
#
# Assigned unconditionally rather than defaulted, so a value arriving in the environment is not a
# verification anyone made here — ModelDownloadIntegrityTest
# .theLedgerRefusesAQuestionNamingNothingAndCountsNothingItDidNotMeasure.
MODEL_MANIFEST_VERIFIED=''

# What fetch_or_degrade REFUSED in this shell, as `<id>:<code>` entries, space-delimited — the
# ledger's mirror, kept under the same discipline: fetch_or_degrade is the only writer,
# configure_retrieval_gps in backend-init.sh the only reader, and it is assigned unconditionally so
# a value arriving in the environment is not a refusal anything here took.
#
# It carries the artifact id and the code from the table below, and nothing else. A PATH in it would
# publish for unverified bytes exactly what require_verified withholds. What it is FOR is the second
# premise of ADR Decision 106's amendment: on a deployment whose container log nobody in the loop
# can read, a blanked path says chart search is off and says nothing about why, so a digest refusal,
# a missing manifest row and a failed transfer are one state seen from outside. The entrypoint
# records this in a global property that is readable over REST.
#
# fetch_and_verify does not write it. The weights go through that one in background subshells, whose
# variables reach no parent shell, so an entry there would be recorded for the embedder and lost for
# the weights — one name answering for two behaviours.
MODEL_MANIFEST_REFUSED=''

# Exit codes fetch_and_verify_url contracts with its callers, which branch on them to say something
# useful about the artifact they asked for:
#   0  the file is present and is the reviewed artifact
#   1  digest mismatch — the file has been deleted
#   2  size mismatch — the file has been deleted, and the caller has a better diagnostic than we do
#   3  the fetch or the placement failed with nothing at the target to begin with, so nothing was
#      verified and nothing was deleted (a non-2xx response is here, because curl runs with -f)
#   4  the artifact could not be resolved, so nothing was fetched: no such id in the manifest, no
#      manifest to read it from, an override with no digest, or — require_url_for_digest — a
#      digest with no url of its own
#   5  the file could not be measured or hashed at all, and is still on disk
#   6  a copy already at the target was refused and DELETED, and its replacement could then not be
#      fetched or placed, so there is now nothing at that name
#
# 1, 2 and 6 are the codes that promise a deletion, and the callers' wording leans on that. 6 is
# the one that also says the deployment LOST something it had: a copy was at this target, it was
# not the recorded artifact, and the pinned revision could then not be reached to replace it. That
# is the fact a "restart and retry" message has to carry, and it is why it is not code 3.

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
# slower — ADR Decision 106 records the rates, and the gap is wide enough to matter over multi-GB
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
		echo "       (a partial download resumed from a different revision reads the same way; the" >&2
		echo "        next start fetches from zero, so report this only if it repeats.)" >&2
		echo "       Refusing it and deleting $1." >&2
		rm -f "$1"
		return 1
	fi
	return 0
}

# fetch_and_verify_url <url> <sha256> <bytes> <target> <label>
#
# The composed step fetch_and_verify and fetch_and_verify_override delegate to, and through them
# what both fetch sites reach: a file is at <target> when this returns 0, and it is the reviewed artifact. Everything
# else returns a code from the table above.
#
# A file already at <target> is verified rather than trusted for its name — see the fall-through
# below, and ADR Decision 106 for why.
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
	# The code to report if no file ends up at the target: 3 while there was nothing there to lose,
	# and 6 once a copy that WAS there has been deleted to make room for a replacement. Carried as a
	# variable rather than re-tested on the disk at each exit, because "is the target missing" is
	# also true of the ordinary first fetch and cannot tell the two apart.
	_mm_gone=3

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
		# Fall through and fetch what the manifest records — ADR Decision 106 for why replacing
		# beats refusing here. A served copy that fails too is the refusal.
		#
		# The copy that was here is already deleted at this point, so from here on a failure to
		# fetch or place is code 6 rather than 3: the deployment has lost a file, which is the one
		# thing a "restart and retry" message must not leave out.
		_mm_gone=6
		echo "Replacing $_mm_label with the artifact $_mm_source records..."
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
		return "$_mm_gone"
	fi

	_mm_verify_file "$_mm_partial" "$_mm_expected" "$_mm_bytes" "$_mm_label" "$_mm_source" || return $?

	mv "$_mm_partial" "$_mm_target" || return "$_mm_gone"
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

# fetch_or_degrade <manifest-id> <target> <label> [diagnostic-line...]
#
# For an artifact CHART SEARCH cannot run without — the querystore embedder and its vocab, whose
# paths configure_retrieval_gps writes into global properties seconds later. Fetches and verifies as
# fetch_and_verify does, and on any refusal says what the code means, says the start goes on
# without chart search, and RETURNS that code. The caller's diagnostic lines are the SIZE message
# and are printed for code 2 alone — ADR Decision 106 for why the size refusal has a message of its
# own.
#
# It returns rather than exiting, and what keeps that fail-closed is the LEDGER, not the exit: a
# refusal records nothing in MODEL_MANIFEST_VERIFIED, so require_verified answers no and
# configure_retrieval_gps WITHDRAWS both embedder paths — and where that withdrawal cannot reach the
# database at all, puts the copy on the volume out of reach instead, which is the only instrument
# left when the row cannot be read. Withholding the write alone would not have been enough once the
# start continues — a row an earlier good start wrote stands whatever this start did, and codes 3, 4
# and 5 delete nothing, so it can still name a file that is still there. Unverified bytes answering
# a clinical question is the state Decision 106 removes; stopping the container also took the whole
# OpenMRS instance and its SPA down, on 2026-09-21, and that decision's amendment says which part
# of the route was observed and which reconstructed.
#
# Still asked of the call site: that this runs in the entrypoint's OWN shell. The ledger is an
# ordinary shell variable, so backgrounding the call with `&`, taking it in a command substitution,
# or making it an element of a pipeline (`| tee`, POSIX running every pipeline element in a
# subshell) records the verification somewhere the shell that publishes the path cannot read — and
# a good download would then configure no retrieval at all. The guard named below refuses all three
# where the call site spells them on the call's own line; a call wrapped in a function that is
# itself backgrounded or piped is not reachable from a line.
#
# What the shell DOES is a behaviour a test drives —
# ModelDownloadIntegrityTest.aRefusalOfTheEmbedderLetsTheStartContinueWithItsPathStillUnpublishable.
# ADR Decision 106 lists the spellings that defeated the source-reading branch this replaced.
fetch_or_degrade() {
	_mm_oe_id=$1
	_mm_oe_target=$2
	_mm_oe_label=$3
	shift 3
	if fetch_and_verify "$_mm_oe_id" "$_mm_oe_target" "$_mm_oe_label"; then
		# Reported HERE, on the branch that knows. The caller used to echo it unconditionally after
		# the call, which was sound only while a refusal exited before reaching it: now that the
		# call returns, that line would say "ready" for an artifact this shell has just refused,
		# and for codes 1, 2 and 6 would measure a copy it has just deleted.
		echo "$_mm_oe_label ready: $_mm_oe_target ($(file_bytes "$_mm_oe_target") bytes)."
		return 0
	else
		_mm_oe_code=$?
		MODEL_MANIFEST_REFUSED="$MODEL_MANIFEST_REFUSED $_mm_oe_id:$_mm_oe_code"
	fi

	echo "       Chart search cannot run without a verified copy of this file, so it stays off:" >&2
	echo "       OpenMRS starts without chart search rather than serving it on bytes nothing" >&2
	echo "       checked. No path to it is left configured — a path an earlier start wrote is" >&2
	echo "       withdrawn too, so nothing loads this file until a start verifies it." >&2
	case $_mm_oe_code in
		2)
			for _mm_oe_line in "$@"; do
				echo "       $_mm_oe_line" >&2
			done
			;;
		4)
			# Code 4 is any failure to RESOLVE the artifact — a missing row, and also a manifest
			# that is not there at all. Both mean the image is built wrong rather than that a
			# fetch went badly, so a restart is not the remedy.
			echo "       It could not be resolved from $MODEL_MANIFEST_FILE — no such row, or no" >&2
			echo "       manifest in the image — so a restart will not help." >&2
			;;
		6)
			# The one refusal that also costs the volume the copy it had. Said here because the
			# lines above read as "chart search stays off on bytes we could not check", which omits
			# the fact that decides whether a restart can recover anything.
			echo "       The copy that was on the volume was refused and deleted, and the pinned" >&2
			echo "       revision could not then be reached to replace it, so there is no copy of" >&2
			echo "       this file left. A restart retries the download." >&2
			;;
	esac
	return "$_mm_oe_code"
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
	fetch_and_verify_url "$_mm_fv_url" "$_mm_fv_sha" "$_mm_fv_bytes" "$2" "$3" || return $?
	# Recorded here rather than one level down because this is where the id is known, and only on
	# the success path — the whole value of the ledger is that it says nothing about a refusal.
	case " $MODEL_MANIFEST_VERIFIED " in
		*" $1 "*) ;;
		*) MODEL_MANIFEST_VERIFIED="$MODEL_MANIFEST_VERIFIED $1" ;;
	esac
	return 0
}

# require_verified <id>... — 0 only when EVERY named artifact verified in this shell, during this
# run. The answer is a fact about what ran, not about where a call is written, which is why it is
# what the entrypoint asks before it writes a model file's path into a global property: rearranging
# the fetches leaves the paths unwritten rather than pointing querystore at unchecked bytes.
#
# The ledger is an ordinary shell variable, so a fetch taken in a SUBSHELL — backgrounded, piped,
# in a command substitution, or inside a function that is any of those — records nothing the parent
# shell can see, and this answers no. That is the direction to fail in, and it is what covers the
# subshell shapes fetch_or_degrade's line-level guard cannot see.
#
# 0 or 1, and 1 is NOT a code from the table above: nothing was fetched, so nothing was refused or
# deleted. Naming no artifact is itself a failure — a call that lost its arguments would otherwise
# answer yes to everything. → ADR Decision 106; ModelDownloadIntegrityTest's ledger cases.
require_verified() {
	if [ "$#" -eq 0 ]; then
		echo "ERROR: require_verified was asked about no artifact at all." >&2
		return 1
	fi
	_mm_rv_unverified=''
	for _mm_rv_id in "$@"; do
		case " $MODEL_MANIFEST_VERIFIED " in
			*" $_mm_rv_id "*) ;;
			*) _mm_rv_unverified="$_mm_rv_unverified $_mm_rv_id" ;;
		esac
	done
	if [ -n "$_mm_rv_unverified" ]; then
		echo "ERROR: not verified in this run:$_mm_rv_unverified" >&2
		echo "       Refusing to publish a path to bytes this start has not checked." >&2
		return 1
	fi
	return 0
}
