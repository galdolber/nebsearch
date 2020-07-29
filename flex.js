              function FlexSearch(options, settings){
            const id = settings ? settings["id"] : options && options["id"];

            this.id = id || (id === 0) ? id : id_counter++;
            this.init(options, settings);

            register_property(this, "index", function(){
                return get_keys(this._ids);
            });

            register_property(this, "length", function(){

                return this.index.length;
            });
        }
function remove_index(map, id){

            if(map){

                const keys = get_keys(map);

                for(let i = 0, lengthKeys = keys.length; i < lengthKeys; i++){

                    const key = keys[i];
                    const tmp = map[key];

                    if(tmp){

                        for(let a = 0, lengthMap = tmp.length; a < lengthMap; a++){

                            if(tmp[a] === id){

                                if(lengthMap === 1){

                                    delete map[key];
                                }
                                else{

                                    tmp.splice(a, 1);
                                }

                                break;
                            }
                            else if(is_object(tmp[a])){

                                remove_index(tmp[a], id);
                            }
                        }
                    }
                }
            }
        }

function intersect(arrays, limit, cursor, suggest, bool, has_and, has_not) {

            let page;
            let result = [];
            let pointer;

            if(cursor === true){

                cursor = "0";
                pointer = "";
            }
            else{

                pointer = cursor && cursor.split(":");
            }

            const length_z = arrays.length;

            if(length_z > 1){
                const check = create_object();
                const suggestions = [];

                let check_not;
                let arr;
                let z = 0;
                let i = 0;
                let length;
                let tmp;
                let init = true;
                let first_result;
                let count = 0;
                let bool_main;
                let last_index;

                let pointer_suggest;
                let pointer_count;

                if(pointer){
                    if(pointer.length === 2){
                        pointer_suggest = pointer;
                        pointer = false;
                    } else{
                        pointer = pointer_count = parseInt(pointer[0], 10);
                    }
                }

                let bool_and;
                let bool_or;
                for(; z < length_z; z++){

                    const is_final_loop = (z === (last_index || length_z) - 1);

                    bool_and = has_and = true;

                    arr = arrays[z];
                    length = arr.length;

                    if(!length){
                        if(bool_and && !suggest){return create_page(cursor, page, arr);}
                        continue;
                    }

                    if(init){
                        if(first_result){
                            const result_length = first_result.length;
                            for(i = 0; i < result_length; i++){

                                const id = first_result[i];
                                const index = "@" + id;
                                if(!has_not || !check_not[index]){

                                    check[index] = 1;

                                    if(!has_and){

                                        result[count++] = id;
                                    }
                                }
                            }
                            first_result = null;
                            init = false;
                        } else{
                            first_result = arr;

                            continue;
                        }
                    }

                    let found = false;

                    for(i = 0; i < length; i++){

                        tmp = arr[i];

                        const index = "@" + tmp;
                        const check_val = has_and ? check[index] || 0 : z;

                        if(check_val || suggest){
                            if(has_not && check_not[index]){continue;}
                            if(!has_and && check[index]){continue;}
                            if(check_val === z){
                                if(is_final_loop || bool_or){
                                    if(!pointer_count || (--pointer_count < count)){

                                        result[count++] = tmp;

                                        if(limit && (count === limit)){

                                            return create_page(cursor, count + (pointer || 0), result);
                                        }
                                    }
                                }
                                else{

                                    check[index] = z + 1;
                                }

                                found = true;
                            }
                            else if(suggest){

                                const current_suggestion = (

                                    suggestions[check_val] || (

                                        suggestions[check_val] = []
                                    )
                                );

                                current_suggestion[current_suggestion.length] = tmp;
                            }
                        }
                    }
                    if(bool_and && !found && !suggest){
                        break;
                    }
                }
                if(first_result){
                    const result_length = first_result.length;
                    if(has_not){
                        if(pointer){

                            i = parseInt(pointer, 10);
                        } else{

                            i = 0;
                        }
                        for(; i < result_length; i++){

                            const id = first_result[i];

                            if(!check_not["@" + id]){

                                result[count++] = id;
                            }
                        }
                    } else{
                        result = first_result;
                    }
                }

                if(suggest){
                    count = result.length;
                    if(pointer_suggest){

                        z = parseInt(pointer_suggest[0], 10) + 1;
                        i = parseInt(pointer_suggest[1], 10) + 1;
                    } else{
                        z = suggestions.length;
                        i = 0;
                    }
		    for(; z--;){
                        tmp = suggestions[z];
                        if(tmp){

                            for(length = tmp.length; i < length; i++){

                                const id = tmp[i];

                                if(!has_not || !check_not["@" + id]){

                                    result[count++] = id;

                                    if(limit && (count === limit)){

                                        return create_page(cursor, z + ":" + i, result);
                                    }
                                }
                            }

                            i = 0;
                        }
                    }
                }
            } else if(length_z){
                if(!bool || ((bool[0] !== "not"))){
                    result = arrays[0];
                    if(pointer){
                        pointer = parseInt(pointer[0], 10);
                    }
                }
            }

            if(limit){
                const length = result.length;
                if(pointer && (pointer > length)){
                    pointer = 0;
                }
                const start = (pointer) || 0;
                page = start + limit;
                if(page < length){
                    result = result.slice(start, page);
                } else {
                    page = 0;
                    if(start){
                        result = result.slice(start);
                    }
                }
            }

            return create_page(cursor, page, result);
        }
