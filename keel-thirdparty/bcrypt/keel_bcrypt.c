/*
 * crypt_blowfish 的 keel 适配层，见 keel_bcrypt.h。
 */

#include <stddef.h>

#include "crypt_blowfish.h"
#include "keel_bcrypt.h"

int keel_bcrypt_hash(const char *password, const char *setting,
	char *output, int output_size)
{
	if (!password || !setting || !output || output_size <= 0)
		return -1;

	return _crypt_blowfish_rn(password, setting, output, output_size) ? 0 : -1;
}

int keel_bcrypt_gensalt(int rounds, const char *input, int input_size,
	char *output, int output_size)
{
	if (!input || !output || output_size <= 0)
		return -1;

	return _crypt_gensalt_blowfish_rn("$2a$", (unsigned long)rounds,
		input, input_size, output, output_size) ? 0 : -1;
}
