if(NOT DEFINED SPIRV_HEADERS_INCLUDE_DIR OR
        NOT EXISTS "${SPIRV_HEADERS_INCLUDE_DIR}/spirv/unified1/spirv.hpp")
    set("SPIRV-Headers_FOUND" FALSE)
    return()
endif()

if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
            INTERFACE_INCLUDE_DIRECTORIES "${SPIRV_HEADERS_INCLUDE_DIR}")
endif()

set("SPIRV-Headers_FOUND" TRUE)
